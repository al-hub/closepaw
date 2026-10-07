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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatGptMcpService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private lateinit var server: McpHttpServer
    private lateinit var tunnel: TunnelProvider

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting ChatGPT connection…"))

        val statusTool = ClosePawStatusTool(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
        )
        server = McpHttpServer(
            port = McpHttpServer.DEFAULT_PORT,
            handler = McpJsonRpcHandler(
                statusTool = statusTool,
                readAppTool = AndroidReadAppTool(),
            ),
        )
        tunnel = CloudflareQuickTunnelProvider(AndroidCloudflaredBinaryResolver(this))

        scope.launch {
            tunnel.status.collect { status ->
                when (status) {
                    TunnelStatus.Stopped -> updateState(
                        ChatGptConnectionState(
                            running = started.get(),
                            phase = if (started.get()) "Local MCP ready" else "Stopped",
                        )
                    )
                    TunnelStatus.Starting -> updateState(
                        ChatGptConnectionState(running = true, phase = "Opening tunnel…")
                    )
                    is TunnelStatus.Connected -> {
                        val endpoint = "${status.publicUrl}${server.mcpPath}"
                        updateState(
                            ChatGptConnectionState(
                                running = true,
                                phase = "Connected",
                                publicMcpUrl = endpoint,
                            )
                        )
                        notifyState("ChatGPT connection ready")
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
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startConnection()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tunnel.stop()
        server.stop()
        started.set(false)
        updateState(ChatGptConnectionState())
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startConnection() {
        if (!started.compareAndSet(false, true)) return
        try {
            server.start()
            updateState(ChatGptConnectionState(running = true, phase = "Local MCP ready"))
            tunnel.start(McpHttpServer.DEFAULT_PORT)
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
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ClosePaw")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun notifyState(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val ACTION_START = "ai.closepaw.chatgpt.START"
        private const val ACTION_STOP = "ai.closepaw.chatgpt.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "closepaw_chatgpt_connection"
        private const val NOTIFICATION_ID = 18424

        private val _connectionState = MutableStateFlow(ChatGptConnectionState())
        internal val connectionState: StateFlow<ChatGptConnectionState> = _connectionState.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ChatGptMcpService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ChatGptMcpService::class.java).setAction(ACTION_STOP)
            )
        }

        private fun updateState(state: ChatGptConnectionState) {
            _connectionState.value = state
        }
    }

    private fun updateState(state: ChatGptConnectionState) {
        Companion.updateState(state)
    }
}
