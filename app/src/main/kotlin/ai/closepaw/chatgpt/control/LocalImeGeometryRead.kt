package ai.closepaw.chatgpt.control

import ai.closepaw.app.AgentService
import ai.closepaw.chatgpt.McpExecutionSettings
import ai.closepaw.platform.GuardedAccessibilityInputMethod
import ai.closepaw.platform.virtualdisplay.ProjectionImageTransport
import ai.closepaw.protocol.SessionState
import ai.closepaw.security.ImageRevocationAuthority
import android.app.KeyguardManager
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Invoked only by the non-exported Settings activity. A waiting run neither
 * holds the screen lease nor opens a fixture. The only captured scene is one
 * blank Chrome editor and its admitted Samsung IME. No control or image cache. */
internal object LocalImeGeometryRead {
    private val idle = setOf(ControlSession.State.IDLE, ControlSession.State.ENDED)
    private val agentBusy = setOf(SessionState.Running, SessionState.TakeoverPending)
    data class Result(val reason: String, val geometry: JsonObject? = null)

    suspend fun run(): Result = withContext(Dispatchers.Main.immediate) {
        val job = currentCoroutineContext()[Job] ?: return@withContext Result("cancelled")
        val service = AgentService.instance ?: return@withContext Result("accessibility_required")
        val remote = service.remoteControlSession
        val remoteEpoch = remote.epoch
        val settings = McpExecutionSettings(service)
        val share = ProjectionImageTransport.active() ?: return@withContext Result("existing_screen_share_required")
        val imageEpoch = ImageRevocationAuthority.currentEpoch
        val keyguard = service.getSystemService(KeyguardManager::class.java)
        val power = service.getSystemService(PowerManager::class.java)
        fun refusal(): String? = when {
            !job.isActive -> "cancelled"
            AgentService.instance !== service -> "accessibility_service_changed"
            remote.epoch != remoteEpoch || remote.state !in idle ||
                service.getActiveSession()?.state?.value in agentBusy -> "other_control_session_active"
            !settings.genericControlEnabled -> "generic_control_required"
            keyguard.isKeyguardLocked || !power.isInteractive -> "device_locked"
            ProjectionImageTransport.active() !== share ||
                !ImageRevocationAuthority.isConsentStillValid(imageEpoch) -> "screen_share_revoked"
            else -> null
        }
        refusal()?.let { return@withContext Result(it) }

        val deadline = SystemClock.elapsedRealtime() + 45_000
        var readySince: Long? = null
        while (true) {
            refusal()?.let { return@withContext Result(it) }
            val now = SystemClock.elapsedRealtime()
            if (now >= deadline) return@withContext Result("chrome_keyboard_deadline")
            if (chromeWithIme(service)) {
                val first = readySince ?: now.also { readySince = it }
                if (now - first >= 1_000) break
            } else readySince = null
            delay(250)
        }

        if (Build.VERSION.SDK_INT < 34) return@withContext Result("secure_window_probe_requires_android14")
        LocalImeGeometryLease.capture(ControlScreenLease.mutex, timeoutMs = 15_000,
            busy = { Result("ui_busy") }, timedOut = { Result("diagnostic_capture_deadline") }) capture@{
            refusal()?.let { return@capture Result(it) }
            val captureJob = currentCoroutineContext()[Job] ?: return@capture Result("cancelled")
            val sceneGeneration = service.remoteDisplayChanges.version()
            val contentGeneration = service.remoteControlChanges.version()
            val input = service.inputMethod as? GuardedAccessibilityInputMethod
                ?: return@capture Result("editor_binding_unavailable")
            val editorGeneration = input.generation
            val revision = settings.runtimeControl.snapshot().revision
            fun stableScene(): String? = refusal() ?: when {
                !captureJob.isActive -> "cancelled"
                settings.runtimeControl.snapshot().revision != revision -> "configuration_revision_changed"
                service.remoteDisplayChanges.version() != sceneGeneration ||
                    service.remoteControlChanges.version() != contentGeneration -> "diagnostic_scene_changed"
                service.inputMethod !== input || input.generation != editorGeneration || !input.currentInputStarted ||
                    input.imagePrivacy?.let { it.packageName == LocalImeGeometryFixture.CHROME && !it.sensitive } != true ->
                    "editor_binding_changed"
                else -> null
            }
            stableScene()?.let { return@capture Result(it) }
            var editorRefusal: (() -> String?)? = null
            fun captureRefusal(): String? = try { stableScene() ?: editorRefusal?.invoke() }
                catch (_: RuntimeException) { "diagnostic_guard_unavailable" }
            val authority = LocalInputAuthority { captureRefusal() == null }
            try {
                val port = AndroidControlPort(service, mutationBlock = { "local_diagnostic_read_only" },
                    runtime = settings.runtimeControl, localAuthority = authority, localImeDiagnostic = true,
                    allowed = { captureJob.isActive })
                try {
                    if (!port.localImeReadAllowed()) return@capture Result("chrome_samsung_read_required")
                    val nativeEditor = withFocusedChromeEditor(service) { AccessibilityNodeInfo.obtain(it) }
                        ?: return@capture Result("blank_editor_required")
                    try {
                        val editor = blankEditor(nativeEditor) ?: return@capture Result("blank_editor_required")
                        val cursorProven = if (KeyboardCursorProof.needsProof(editor))
                            KeyboardCursorBinding.prove(service, editor, nativeEditor) == editorGeneration else false
                        if (!LocalImeGeometryFixture.emptyCaret(editor, cursorProven))
                            return@capture Result("blank_editor_required")
                        editorRefusal = {
                            if (!port.localImeReadAllowed()) "chrome_samsung_read_required"
                            else if (withFocusedChromeEditor(service) { fresh ->
                                    val current = blankEditor(fresh)
                                    fresh == nativeEditor && current != null &&
                                        editor.copy(selectionStart = current.selectionStart,
                                            selectionEnd = current.selectionEnd) == current &&
                                        LocalImeGeometryFixture.emptyCaret(current, cursorProven)
                                } == true) null else "editor_binding_changed"
                        }
                        captureRefusal()?.let { return@capture Result(it) }
                        val (frame, tree) = port.captureLocalImeGeometry()
                        captureRefusal()?.let { return@capture Result(it) }
                        LocalImeGeometryFixture.refusal(frame, cursorProven)?.let { return@capture Result(it) }
                        val admitted = frame.inputTarget ?: return@capture Result("editor_binding_changed")
                        if (frame.windowId != editor.sourceWindowId || admitted.bounds != editor.bounds ||
                            admitted.resourceId != editor.resourceId || admitted.className != editor.className)
                            return@capture Result("editor_binding_changed")
                        val proof = port.visualKeyboard(frame)
                        captureRefusal()?.let { return@capture Result(it) }
                        if (proof == null) return@capture Result(fixedReason(port.visualFailure()))
                        val captured = frame.copy(visual = proof)
                        if (!port.visualDisclosureAllowed(captured)) return@capture Result("keyboard_proof_revoked")
                        val geometry = ImeGeometryReport.collect(frame, tree, proof)
                        captureRefusal()?.let { return@capture Result(it) }
                        if (!port.visualDisclosureAllowed(captured)) return@capture Result("keyboard_proof_revoked")
                        Result("geometry_recorded", geometry)
                    } finally {
                        editorRefusal = null
                        nativeEditor.recycle()
                    }
                } finally { port.invalidate() }
            } finally { authority.close() }
        }
    }

    // Only this exact editable Chrome focus is read, and only its blankness,
    // caret and structural identity are retained for the running capture.
    @RequiresApi(34)
    private inline fun <T> withFocusedChromeEditor(service: AgentService, read: (AccessibilityNodeInfo) -> T): T? {
        val root = service.rootInActiveWindow ?: return null
        var focused: AccessibilityNodeInfo? = null
        return try {
            if (root.packageName?.toString() != LocalImeGeometryFixture.CHROME) return null
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
            focused = node
            if (!node.refresh() || node.windowId != root.windowId ||
                node.packageName?.toString() != LocalImeGeometryFixture.CHROME) null else read(node)
        } finally {
            if (focused !== root) focused?.recycle()
            root.recycle()
        }
    }

    @RequiresApi(34)
    private fun blankEditor(node: AccessibilityNodeInfo): ControlNode? {
        if (!node.isVisibleToUser || !node.isEnabled || !node.isFocusable || !node.isFocused ||
            !node.isEditable || node.isPassword || node.isAccessibilityDataSensitive ||
            (!node.isShowingHintText && !node.text.isNullOrEmpty())) return null
        return ControlNode(0, resourceId = node.viewIdResourceName.orEmpty().take(100),
            className = node.className?.toString().orEmpty(), bounds = Rect().also(node::getBoundsInScreen).flattenToString(),
            enabled = true, empty = true, editable = true, focusable = true, focused = true,
            sourceWindowId = node.windowId, sourcePackage = LocalImeGeometryFixture.CHROME,
            selectionStart = node.textSelectionStart, selectionEnd = node.textSelectionEnd)
    }

    private fun chromeWithIme(service: AgentService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val chrome = try { root.packageName?.toString() == LocalImeGeometryFixture.CHROME } finally { root.recycle() }
        if (!chrome) return false
        val windows = service.windows.orEmpty()
        return try {
            windows.count { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.displayId == 0 } == 1
        } finally { windows.forEach { it.recycle() } }
    }

    private fun fixedReason(value: String) = value.takeIf { it.matches(Regex("[a-z0-9_]{1,100}")) }
        ?: "keyboard_capture_refused"
}
