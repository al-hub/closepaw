package ai.closepaw.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.SurfaceView
import android.view.accessibility.AccessibilityEvent
import ai.closepaw.debug.ActionDebugReceiver
import ai.closepaw.protocol.Op
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.session.AgentSession
import ai.closepaw.ui.overlay.compose.IslandOverlayHost
import ai.closepaw.ui.overlay.compose.ServiceLifecycleOwner
import ai.closepaw.ui.overlay.visualizer.ActionVisualizerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AgentService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentService"

        const val ACTION_STOP_AGENT = "ai.closepaw.STOP_AGENT"

        private const val OUR_PACKAGE = "ai.closepaw"
        private const val SHUTDOWN_TIMEOUT_MS = 5_000L

        @Volatile
        var instance: AgentService? = null
            private set

        private val _statusFlow = MutableStateFlow<String>("")
        val statusFlow: StateFlow<String> = _statusFlow.asStateFlow()
    }

    private val _effectivePlatformMode = MutableStateFlow<PlatformMode?>(null)
    val effectivePlatformMode: StateFlow<PlatformMode?> = _effectivePlatformMode.asStateFlow()

    /**
     * Signal that the VirtualDisplayViewerActivity should finish itself. Emitted by the
     * overlay controller when the agent reaches a terminal idle state with the viewer
     * still in front (see [shouldFinishViewerOnIdle]). The activity collects this in its
     * lifecycle scope and calls finish() so the user lands back on MainActivity instead
     * of being stranded on a frozen VD surface.
     *
     * SharedFlow with replay=0 + buffer=1 + DROP_OLDEST so a missed signal during
     * activity recreation doesn't leak — the next emit replaces it, and we only ever
     * care about the most recent finish request.
     */
    private val _viewerFinishSignal = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val viewerFinishSignal: SharedFlow<Unit> = _viewerFinishSignal.asSharedFlow()

    /**
     * Long-lived service-owned coroutine scope. Cancelled in [onDestroy].
     *
     * Exposed as `internal` so [AgentSession] callers in the app module can
     * scope sessions to the service's lifetime instead of an Activity's. This
     * is essential: a session created against `MainActivity.sessionScope`
     * dies on rotation/recreation, killing in-flight LLM streams.
     */
    internal val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val serviceLifecycleOwner = ServiceLifecycleOwner()
    private var session: AgentSession? = null
    private var overlayController: ServiceOverlayController? = null
    private var modernEmergencyXVisible = false
    private var actionVisualizer: ActionVisualizerManager? = null
    internal val remoteControlChanges = ai.closepaw.chatgpt.control.ControlChangeSignal()
    internal val remoteControlSession: ai.closepaw.chatgpt.control.ControlSession by lazy {
        val prefs = getSharedPreferences("remote-control-session", MODE_PRIVATE)
        ai.closepaw.chatgpt.control.ControlSession(prefs.getString("state", "idle") ?: "idle") {
            prefs.edit().putString("state", it).apply()
        }.apply { changed = { serviceScope.launch {
            if (remoteControlSession.state !in setOf(ai.closepaw.chatgpt.control.ControlSession.State.ACTIVE,
                    ai.closepaw.chatgpt.control.ControlSession.State.IDLE)) actionVisualizer?.clear()
            showRemoteControlActivity()
        } } }
    }
    internal var remoteResumeCheck: (suspend () -> Boolean)? = null
    private val resumeDiagnostics = ArrayDeque<String>()
    private fun recordResumeDiagnostic(code: String, elapsedMs: Long) {
        val entry = "resume=$code elapsed_ms=$elapsedMs state=${remoteControlSession.state.name}"
        if (resumeDiagnostics.size >= 16) resumeDiagnostics.removeFirst()
        resumeDiagnostics.addLast(entry)
        Log.i(TAG, entry)
    }
    internal fun recentResumeDiagnostics(): List<String> = resumeDiagnostics.toList()

    /** Read-only, content-free diagnostics; never resumes an AI session. */
    fun controlRecoveryReport(): String {
        val unlocked = !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
            getSystemService(android.os.PowerManager::class.java).isInteractive
        return buildString {
            append("접근성: ").append(if (isServiceActive) "연결됨" else "확인 필요").append('\n')
            append("화면: ").append(if (unlocked) "켜짐/잠금 해제" else "잠금 또는 꺼짐").append('\n')
            append("제어: ").append(remoteControlSession.state.name).append('\n')
            append("패널: ").append(if (remoteControlIndicator.isAttached()) "표시됨" else "표시되지 않음").append('\n')
            append("최근 진단: ").append(resumeDiagnostics.lastOrNull() ?: "없음")
        }
    }
    /** User-triggered panel recovery; PAUSED remains PAUSED. */
    fun restorePausedControlPanel(): Boolean {
        if (!isServiceActive ||
            remoteControlSession.state != ai.closepaw.chatgpt.control.ControlSession.State.PAUSED) return false
        remoteControlIndicator.restorePanel(showEmergencyX = !modernEmergencyXVisible)
        return remoteControlIndicator.isAttached()
    }

    private val remoteControlIndicator by lazy { RemoteControlIndicator(this, remoteControlSession, stopOtherSessions = {
        if (overlayController?.stateHolder?.hasActiveTask == true) submitOp(Op.Shutdown)
    }) {
        if (remoteControlSession.beginResume()) serviceScope.launch {
            val epoch = remoteControlSession.epoch
            val started = android.os.SystemClock.elapsedRealtime()
            val safe = try {
                val check = remoteResumeCheck
                val result = if (check == null) null else
                    kotlinx.coroutines.withTimeoutOrNull(5_000L) { check() }
                val code = when {
                    check == null -> "resume_check_unavailable"
                    result == null -> "screen_check_timeout"
                    result -> "safe"
                    else -> "screen_check_failed"
                }
                recordResumeDiagnostic(code, android.os.SystemClock.elapsedRealtime() - started)
                result == true
            } catch (e: kotlinx.coroutines.CancellationException) {
                recordResumeDiagnostic("cancelled", android.os.SystemClock.elapsedRealtime() - started)
                throw e
            } catch (e: Exception) {
                recordResumeDiagnostic("exception_" + e.javaClass.simpleName.take(32),
                    android.os.SystemClock.elapsedRealtime() - started)
                false
            }
            remoteControlSession.finishResume(epoch, safe)
            if (!safe) restorePausedControlPanel()
        }
    } }
    internal fun showRemoteControlAction(rect: android.graphics.Rect, kind: String, acknowledged: Boolean? = null) {
        runCatching { remoteControlIndicator.action(rect, kind, acknowledged) }
    }
    internal fun showRemoteControlTap(rect: android.graphics.Rect, x: Float, y: Float) {
        runCatching { remoteControlIndicator.tap(rect, x, y) }
    }
    internal fun showRemoteControlTarget(rect: android.graphics.Rect) { runCatching { remoteControlIndicator.target(rect) } }
    internal fun remoteControlCovers(rect: android.graphics.Rect) = remoteControlIndicator.controlCovers(rect) ||
        overlayController?.controlCovers(rect) == true
    internal fun showRemoteControlActivity() {
        val unlocked = !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
            getSystemService(android.os.PowerManager::class.java).isInteractive
        val active = remoteControlSession.state !in setOf(
            ai.closepaw.chatgpt.control.ControlSession.State.IDLE,
            ai.closepaw.chatgpt.control.ControlSession.State.ENDED)
        overlayController?.setRemoteStatus(if (unlocked && active) remoteControlSession.label else null)
        if (!unlocked) { remoteControlIndicator.hide(); return }
        remoteControlIndicator.show(showEmergencyX = !modernEmergencyXVisible)
        if (remoteControlSession.state == ai.closepaw.chatgpt.control.ControlSession.State.PAUSED)
            remoteControlIndicator.restorePanel(showEmergencyX = !modernEmergencyXVisible)
    }
    internal fun observedIsland() = overlayController?.observedIsland()
    internal suspend fun dragIsland(before: ai.closepaw.chatgpt.control.IslandBounds, dx: Int, dy: Int,
        holdMs: Long, moveMs: Long, permitted: () -> Boolean): Boolean =
        overlayController?.dragIsland(before, dx, dy, holdMs, moveMs, permitted) ?: false

    internal fun showRemoteControlFeedbackTest() { remoteControlIndicator.show(testOnly = true) }
    internal fun hideRemoteControlActivity(force: Boolean = false) {
        if (force || remoteControlSession.state in setOf(ai.closepaw.chatgpt.control.ControlSession.State.IDLE,
                ai.closepaw.chatgpt.control.ControlSession.State.ENDED)) {
            remoteControlIndicator.hide()
            overlayController?.setRemoteStatus(null)
        }
    }
    internal fun isRemoteControlActivityAttached() = remoteControlIndicator.isAttached()
    internal fun ownsRemoteControlIndicatorWindow(id: Int) = remoteControlIndicator.ownsWindow(id) ||
        overlayController?.ownsControlWindow(id) == true
    private var currentPlatformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    @Volatile private var isServiceActive = false
    private val viewerBridge by lazy {
        AgentServiceViewerBridge(
            logTag = TAG,
            overlayControllerProvider = { overlayController },
            platformProvider = { session?.getServices()?.platform as? ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform },
            openViewerActivity = { openViewerActivity() },
            finishViewerActivity = { _viewerFinishSignal.tryEmit(Unit) },
        )
    }
    private val eventHandler =
            AgentServiceEventHandler(
                    logTag = TAG,
                    updateStatus = ::updateStatus,
                    sessionCleared = {
                        session = null
                        _effectivePlatformMode.value = null
                    },
                    overlayController = { overlayController }
            )

    val capsuleStateHolder get() = overlayController?.stateHolder

    fun getActiveSession(): AgentSession? = session

    internal fun getActionVisualizer(): ActionVisualizerManager? = actionVisualizer

    internal fun getOverlayTouchGate(): ai.closepaw.platform.OverlayTouchGate? =
            overlayController?.overlayTouchGate

    internal fun dismissError() {
        overlayController?.dismissError()
    }

    private var eventCollectorJob: Job? = null

    fun observeExternalSession(
            externalSession: AgentSession,
            platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    ) {
        if (!isServiceActive) {
            Log.w(TAG, "Ignoring observeExternalSession while service is shutting down")
            return
        }
        Log.i(TAG, "Observing external session: ${externalSession.sessionId}, mode=$platformMode")
        session = externalSession
        currentPlatformMode = platformMode
        _effectivePlatformMode.value = platformMode
        overlayController?.setPlatformMode(platformMode)
        observeSession(externalSession)
    }

    private val stopReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == ACTION_STOP_AGENT) {
                        Log.i(TAG, "Received STOP_AGENT broadcast")
                        stopAgent()
                    }
                }
            }

    private val debugExecReceiver = ActionDebugReceiver()
    private val debugMobileActionReceiver = ai.closepaw.debug.MobileActionDebugReceiver()

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceActive = true
        instance = this
        Log.i(TAG, "AgentService connected")

        val info = serviceInfo
        if (info != null) {
            info.flags =
                    info.flags or
                            android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                            android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            serviceInfo = info
            Log.i(TAG, "Updated service info flags: ${info.flags}")
        } else {
            Log.w(TAG, "Service info was null!")
        }

        updateStatus("Accessibility Service connected")

        val controller =
                ServiceOverlayController(
                        context = this,
                        lifecycleOwner = serviceLifecycleOwner,
                        savedStateRegistryOwner = serviceLifecycleOwner,
                        scope = serviceScope,
                        appPackage = OUR_PACKAGE,
                        logTag = TAG,
                        onEmergencyXVisibilityChanged = { visible ->
                            modernEmergencyXVisible = visible
                            if (visible) remoteControlIndicator.hideEmergencyXOnly()
                            else if (!getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
                                     getSystemService(android.os.PowerManager::class.java).isInteractive && remoteControlSession.state != ai.closepaw.chatgpt.control.ControlSession.State.IDLE &&
                                     remoteControlSession.state != ai.closepaw.chatgpt.control.ControlSession.State.ENDED)
                                remoteControlIndicator.show(showEmergencyX = true)
                        },
                        onStop = {
                            // A single visible emergency X must stop both independently
                            // tracked control sessions, not just the agent task.
                            remoteControlSession.end()
                            hideRemoteControlActivity(force = true)
                            submitOp(Op.Shutdown)
                        },
                        onTakeover = { submitOp(Op.Takeover) },
                        onResume = { submitOp(Op.Resume) },
                        onSupplement = { text -> submitOp(Op.Supplement(text)) },
                        onSupplementAndResume = { text ->
                            submitOps(Op.Supplement(text), Op.Resume)
                        },
                        onUserResponse = { callId, response -> submitOp(Op.UserResponse(callId, response)) },
                        onApprovalResponse = { callId, decision, scope, packageName ->
                            submitOp(Op.Approve(callId, decision, scope, packageName))
                        },
                        onOpenApp = {
                            val intent =
                                    Intent(this, MainActivity::class.java).apply {
                                        flags =
                                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    }
                            startActivity(intent)
                        },
                        onOpenViewer = { viewerBridge.openViewer() },
                        onFinishViewer = { viewerBridge.finishViewer() },
                        statusIslandManager =
                                IslandOverlayHost(
                                        service = this,
                                        lifecycleOwner = serviceLifecycleOwner,
                                        savedStateRegistryOwner = serviceLifecycleOwner,
                                        onExpandCapsule = {
                                            // Tap island → expand Smart Capsule overlay, hide island
                                            overlayController?.onIslandTapped()
                                        }
                                )
                )
        overlayController = controller

        actionVisualizer = ActionVisualizerManager(
                context = this,
                lifecycleOwner = serviceLifecycleOwner,
                savedStateRegistryOwner = serviceLifecycleOwner,
                renderContext = controller.stateHolder.context,
                renderAllowed = { remoteControlSession.state in setOf(
                    ai.closepaw.chatgpt.control.ControlSession.State.ACTIVE,
                    ai.closepaw.chatgpt.control.ControlSession.State.IDLE) },
        )
        Log.i(TAG, "ActionVisualizerManager initialized")

        registerDebugStopReceiverIfNeeded(this, stopReceiver)
        registerDebugExecReceiverIfNeeded(this, debugExecReceiver)
        registerDebugMobileActionReceiverIfNeeded(this, debugMobileActionReceiver)
        showRemoteControlActivity()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No node reads or text copying in the callback; events only wake the bounded observer.
        if (event != null && event.packageName?.toString() != packageName &&
            event.eventType in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            remoteControlChanges.changed()
        }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val eventDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                event.displayId
            } else {
                Display.DEFAULT_DISPLAY
            }
            overlayController?.handleWindowStateChanged(
                    packageName = event.packageName?.toString(),
                    className = event.className?.toString(),
                    displayId = eventDisplayId,
            )
        }
    }

    override fun onInterrupt() {
        remoteControlSession.end()
        hideRemoteControlActivity(force = true)
        remoteControlChanges.changed()
        Log.w(TAG, "AgentService interrupted")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(controlScreenReceiver) }
        remoteControlSession.end()
        hideRemoteControlActivity(force = true)
        remoteControlChanges.changed()
        isServiceActive = false
        instance = null

        eventCollectorJob?.cancel()
        eventCollectorJob = null

        val currentSession = session
        if (currentSession != null) {
            // Detach shutdown onto a scope that outlives `serviceScope`. The session handles
            // its own checkpoint persistence via NonCancellable, so the main goal here
            // is to avoid blocking the main thread (ANR risk).
            val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            shutdownScope.launch {
                try {
                    val completed = withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                        currentSession.submit(Op.Shutdown)
                        true
                    }
                    if (completed != true) {
                        Log.w(TAG, "Timed out waiting for session shutdown")
                    }
                } finally {
                    shutdownScope.cancel()
                }
            }
        }
        session = null
        _effectivePlatformMode.value = null

        overlayController?.dispose()
        overlayController = null
        actionVisualizer?.dispose()
        actionVisualizer = null
        unregisterDebugStopReceiverIfNeeded(this, stopReceiver)
        unregisterDebugExecReceiverIfNeeded(this, debugExecReceiver)
        unregisterDebugMobileActionReceiverIfNeeded(this, debugMobileActionReceiver)
        serviceLifecycleOwner.onDestroy()
        super.onDestroy()
        _statusFlow.value = ""
        serviceScope.cancel()
    }

    private val controlScreenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == android.content.Intent.ACTION_SCREEN_OFF) {
                remoteControlIndicator.hide()
                actionVisualizer?.clear()
            } else showRemoteControlActivity()
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceLifecycleOwner.onCreate()
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(controlScreenReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(controlScreenReceiver, filter) }
    }

    private fun submitOp(op: Op) {
        submitOps(op)
    }

    private fun submitOps(vararg ops: Op) {
        if (!isServiceActive) {
            Log.w(TAG, "Dropping ops while service is not active: ${ops.toList()}")
            return
        }
        val currentSession = session
        Log.d(TAG, "submitOps: ${ops.toList()}, session=${currentSession?.sessionId}")

        if (currentSession == null) {
            Log.w(TAG, "No active session for ops: ${ops.toList()}")
            return
        }

        serviceScope.launch {
            ops.forEach { op -> currentSession.submit(op) }
        }
    }

    private fun updateStatus(status: String) {
        Log.d(TAG, status)
        _statusFlow.value = status
    }

    private fun observeSession(agentSession: AgentSession) {
        eventCollectorJob?.cancel()

        val recordingService = agentSession.getServices().recordingService

        eventCollectorJob =
                serviceScope.launch {
                    try {
                        agentSession.events.collect { event ->
                            try {
                                eventHandler.handleEvent(event, recordingService)
                            } catch (e: Exception) {
                                Log.e(
                                        TAG,
                                        "Failed to handle event: ${event::class.simpleName}",
                                        e
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Session event collector crashed", e)
                    }
                }
    }

    fun runAgent(
            goal: String,
            authStore: ai.closepaw.auth.AuthStore? = null,
            platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
    ) {
        if (!isServiceActive) {
            Log.w(TAG, "Ignoring runAgent because service is not active")
            return
        }
        if (session != null) {
            Log.i(TAG, "Stopping existing session before starting new one")
            eventCollectorJob?.cancel()
            eventCollectorJob = null
            val oldSession = session
            session = null
            serviceScope.launch { oldSession?.submit(Op.Shutdown) }
        }

        currentPlatformMode = platformMode
        overlayController?.setPlatformMode(platformMode)

        serviceScope.launch {
            try {
                val settings = AppSettingsStore(this@AgentService).load()
                val sessionConfig =
                        SessionConfig(
                                debugMode = true,
                                traceEnabled = settings.traceEnabled,
                                platformMode = platformMode
                        )
                val visualizer = actionVisualizer
                val touchGate = overlayController?.overlayTouchGate
                val newSession =
                        withContext(Dispatchers.Default) {
                            AgentSession.create(
                                    config = sessionConfig,
                                    service = this@AgentService,
                                    scope = serviceScope,
                                    authStore = authStore,
                                    visualizer = visualizer,
                                    overlayTouchGate = touchGate,
                            )
                        }

                session = newSession

                observeSession(newSession)

                newSession.submit(Op.UserInput(text = goal))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create session", e)
                updateStatus("❌ Failed to start: ${e.message}")
                overlayController?.hideAll()
            }
        }
    }

    fun stopAgent() {
        submitOp(Op.Shutdown)
        overlayController?.hideAll()
        updateStatus("🛑 Agent stopped")
    }

    fun onViewerOpened() {
        viewerBridge.onViewerOpened()
    }

    /**
     * Synchronous version of the [shouldFinishViewerOnIdle] rule. Used by
     * VirtualDisplayViewerActivity at onStart to race-proof the SharedFlow path: if the
     * agent is already idle when the user opens the viewer, the SharedFlow emit may
     * happen before the activity's collector subscribes, so we also poll directly here.
     */
    fun shouldFinishViewerNow(): Boolean = overlayController?.shouldFinishViewerNow() == true

    fun onViewerClosed() {
        viewerBridge.onViewerClosed()
    }

    fun onMainAppVisible() {
        viewerBridge.onMainAppVisible()
    }

    /**
     * Bring MainActivity to the foreground and ask it to prompt for RECORD_AUDIO. Used by the
     * overlay path — `RequestPermission` is an Activity-result contract, so the prompt cannot
     * be launched from this Service. MainActivity reads the extra in onCreate/onNewIntent and
     * fires the launcher once Compose is mounted.
     */
    fun requestVoicePermissionViaMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_REQUEST_VOICE_PERMISSION, true)
        }
        startActivity(intent)
    }

    fun onMainAppHidden() {
        viewerBridge.onMainAppHidden()
    }

    private fun openViewerActivity() {
        try {
            val intent =
                    Intent().setClassName(
                                    this,
                                    "ai.closepaw.ui.viewer.VirtualDisplayViewerActivity"
                            )
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open viewer", e)
        }
    }

    fun notifyViewerVisible(surfaceView: SurfaceView) {
        viewerBridge.notifyViewerVisible(surfaceView)
    }

    fun notifyViewerHidden() {
        viewerBridge.notifyViewerHidden()
    }

    fun onViewerTouch(
            action: Int,
            x: Float,
            y: Float,
            downTime: Long,
            eventTime: Long,
            viewWidth: Int,
            viewHeight: Int,
    ): Boolean {
        return viewerBridge.onViewerTouch(
                action = action,
                x = x,
                y = y,
                downTime = downTime,
                eventTime = eventTime,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
        )
    }

}
