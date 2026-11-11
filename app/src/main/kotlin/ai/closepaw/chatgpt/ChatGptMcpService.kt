package ai.closepaw.chatgpt

import ai.closepaw.BuildConfig
import ai.closepaw.R
import ai.closepaw.app.MainActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatGptMcpService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private lateinit var server: McpHttpServer
    private lateinit var tunnel: TunnelProvider
    private lateinit var reconnect: TunnelReconnectController

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting ChatGPT connection…"))

        val statusTool = ClosePawStatusTool(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
        )
        val executionSettings = McpExecutionSettings(this)
        val reader = AndroidReadAppTool()
        val readLock = Any()
        val readApp = ReadAppTool { app -> synchronized(readLock) { reader.read(app) } }
        val operations = AndroidOperationRuntime(this, scope, readApp, executionSettings)
        server = McpHttpServer(
            port = McpHttpServer.DEFAULT_PORT,
            mcpPath = executionSettings.endpointPath(),
            handler = McpJsonRpcHandler(
                runtimeInfoEnabled = true,
                statusTool = statusTool,
                readAppTool = readApp,
                operationTools = operations,
                extraDiagnostics = { kotlinx.serialization.json.buildJsonObject {
                    put("gemini_draft", operations.diagnosticSnapshot())
                    put("tailscale_recovery", operations.tailscaleDiagnosticSnapshot())
                    put("generic_control", ai.closepaw.chatgpt.control.ControlDiagnostics.snapshot())
                } },
            ),
        )
        val secureConfigStore = SecureMcpTunnelConfigStore(this)
        val secureConfig = runCatching { secureConfigStore.load() }.getOrNull()
        tunnel = if (secureConfig != null) {
            OpenAiSecureTunnelProvider(
                binaryResolver = AndroidOpenAiTunnelBinaryResolver(this),
                configProvider = { secureConfig },
                mcpPath = server.mcpPath,
                scope = scope,
            )
        } else {
            CloudflareQuickTunnelProvider(AndroidCloudflaredBinaryResolver(this), scope = scope)
        }
        reconnect = TunnelReconnectController(
            scope, tunnel, { ChatGptConnectionSettings(this).enabled },
            onFailure = { reason ->
                updateState { current ->
                    if (connectionActive()) ChatGptConnectionState(
                        running = true, phase = "Local MCP ready", error = reason,
                    ) else current
                }
            },
            onRetry = { waitMs, reason ->
                updateState { current ->
                    if (connectionActive()) ChatGptConnectionState(
                        running = true, phase = "Reconnecting in ${waitMs / 1000}s…", error = reason,
                    ) else current
                }
                if (connectionActive()) notifyState("Reconnecting in ${waitMs / 1000}s…")
            },
        )

        scope.launch {
            tunnel.status.collect { status ->
                when (status) {
                    TunnelStatus.Stopped -> updateState { current ->
                        if (connectionActive()) ChatGptConnectionState(
                            running = true, phase = "Local MCP ready", error = current.error,
                        ) else current
                    }
                    TunnelStatus.Starting -> updateState(
                        ChatGptConnectionState(running = true, phase = "Opening tunnel…")
                    )
                    is TunnelStatus.Connected -> {
                        val endpoint = status.publicUrl?.let { "$it${server.mcpPath}" }
                        val secure = status.tunnelId != null
                        updateState(
                            ChatGptConnectionState(
                                running = true,
                                phase = if (secure) "Secure tunnel runtime ready" else "Connected",
                                publicMcpUrl = endpoint,
                                tunnelId = status.tunnelId,
                                transport = if (secure) "OpenAI Secure MCP Tunnel" else "Cloudflare Quick Tunnel",
                            )
                        )
                        notifyState(
                            if (secure) "Secure tunnel runtime ready" else "ChatGPT connection ready"
                        )
                    }
                    is TunnelStatus.Failed -> updateState(
                        ChatGptConnectionState(
                            running = true,
                            phase = "Local MCP ready",
                            error = status.reason,
                        )
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CONTROL_STOP) {
            ai.closepaw.app.AgentService.instance?.stopRemoteAndLocalControl()
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable + Dispatchers.IO) { stop(applicationContext) }
            }
            return START_NOT_STICKY
        }
        if (!ChatGptConnectionSettings(this).enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        startConnection()
        return START_STICKY
    }

    override fun onDestroy() {
        reconnect.stop()
        server.stop()
        started.set(false)
        updateState(if (ChatGptConnectionSettings.persistenceBlocked) intentPersistenceFailure()
            else ChatGptConnectionState())
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startConnection() {
        if (!started.compareAndSet(false, true)) return
        try {
            server.start()
            updateState(ChatGptConnectionState(running = true, phase = "Local MCP ready"))
            reconnect.start(McpHttpServer.DEFAULT_PORT)
        } catch (error: Exception) {
            started.set(false)
            server.stop()
            updateState(
                ChatGptConnectionState(
                    running = false,
                    phase = "Failed",
                    error = "mcp_server_start_failed:${error.javaClass.simpleName}",
                )
            )
            stopSelf()
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "ChatGPT connection",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ChatGptMcpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val controlStop = PendingIntent.getService(this, 2,
            Intent(this, ChatGptMcpService::class.java).setAction(ACTION_CONTROL_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ClosePaw")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "AI 제어 종료", controlStop).build())
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun notifyState(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val ACTION_START = "ai.closepaw.chatgpt.START"
        private const val ACTION_CONTROL_STOP = "ai.closepaw.chatgpt.CONTROL_STOP"
        private const val ACTION_STOP = "ai.closepaw.chatgpt.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "closepaw_chatgpt_connection"
        private const val NOTIFICATION_ID = 18424

        private val _connectionState = MutableStateFlow(ChatGptConnectionState())
        internal val connectionState: StateFlow<ChatGptConnectionState> = _connectionState.asStateFlow()

        /** Invoked on IO by the device UI; never authorize startup after a failed write. */
        @Synchronized fun start(context: Context) {
            if (!ChatGptConnectionSettings(context).persistEnabled(true)) {
                updateState(intentPersistenceFailure())
                return
            }
            requestConnectionStart("connect_start_blocked") { requestStart(context) }
        }

        internal fun requestConnectionStart(failurePrefix: String, request: () -> Unit): Boolean =
            runCatching { request() }.onFailure { error ->
                updateState(ChatGptConnectionState(
                    phase = "Open ClosePaw to reconnect",
                    error = "$failurePrefix:${error.javaClass.simpleName}",
                ))
            }.isSuccess

        private fun requestStart(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ChatGptMcpService::class.java).setAction(ACTION_START),
            )
        }

        /** Invoked on IO. Stop the service even when storage cannot acknowledge durability. */
        @Synchronized fun stop(context: Context): Boolean {
            val saved = ChatGptConnectionSettings(context).persistEnabled(false)
            context.stopService(Intent(context, ChatGptMcpService::class.java))
            updateState(if (saved) ChatGptConnectionState() else intentPersistenceFailure())
            return saved
        }

        private fun intentPersistenceFailure() = ChatGptConnectionState(
            phase = "Stopped; connection preference could not be saved",
            error = "connection_intent_persistence_failed",
        )

        suspend fun restore(context: Context) = withContext(Dispatchers.IO) {
            if (!ChatGptConnectionSettings(context).enabled) return@withContext
            val configured = runCatching { SecureMcpTunnelConfigStore(context).load() != null }.getOrDefault(false)
            synchronized(Companion) {
                if (ChatGptAutoConnectPolicy.shouldRestore(configured, ChatGptConnectionSettings(context).enabled)) {
                    requestConnectionStart("auto_connect_blocked") { requestStart(context) }
                }
            }
        }

        private fun updateState(state: ChatGptConnectionState) {
            _connectionState.value = state
        }

        private fun updateState(transform: (ChatGptConnectionState) -> ChatGptConnectionState) {
            _connectionState.update(transform)
        }
    }

    private fun connectionActive() =
        started.get() && scope.isActive && ChatGptConnectionSettings(this).enabled

    private fun updateState(state: ChatGptConnectionState) {
        Companion.updateState(state)
    }

    private fun updateState(transform: (ChatGptConnectionState) -> ChatGptConnectionState) {
        Companion.updateState(transform)
    }
}
