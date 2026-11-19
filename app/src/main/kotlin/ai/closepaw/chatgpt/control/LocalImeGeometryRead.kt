package ai.closepaw.chatgpt.control

import ai.closepaw.app.AgentService
import ai.closepaw.chatgpt.McpExecutionSettings
import ai.closepaw.platform.virtualdisplay.ProjectionImageTransport
import ai.closepaw.protocol.SessionState
import ai.closepaw.security.ImageRevocationAuthority
import android.app.KeyguardManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
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

        if (!ControlScreenLease.mutex.tryLock()) return@withContext Result("ui_busy")
        try {
            refusal()?.let { return@withContext Result(it) }
            val authority = LocalInputAuthority { refusal() == null }
            try {
                val port = AndroidControlPort(service, mutationBlock = { "local_diagnostic_read_only" },
                    runtime = settings.runtimeControl, localAuthority = authority, localImeDiagnostic = true,
                    allowed = { refusal() == null })
                try {
                    val (frame, tree) = port.captureLocalImeGeometry()
                    refusal()?.let { return@withContext Result(it) }
                    LocalImeGeometryFixture.refusal(frame)?.let { return@withContext Result(it) }
                    val proof = port.visualKeyboard(frame)
                    refusal()?.let { return@withContext Result(it) }
                    if (proof == null) return@withContext Result(fixedReason(port.visualFailure()))
                    val captured = frame.copy(visual = proof)
                    if (!port.visualDisclosureAllowed(captured)) return@withContext Result("keyboard_proof_revoked")
                    val geometry = ImeGeometryReport.collect(frame, tree, proof)
                    refusal()?.let { return@withContext Result(it) }
                    if (!port.visualDisclosureAllowed(captured)) return@withContext Result("keyboard_proof_revoked")
                    Result("geometry_recorded", geometry)
                } finally { port.invalidate() }
            } finally { authority.close() }
        } finally { ControlScreenLease.mutex.unlock() }
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
