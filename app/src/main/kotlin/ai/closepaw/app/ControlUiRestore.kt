package ai.closepaw.app

import kotlinx.coroutines.delay

/**
 * SCREEN_ON/USER_PRESENT may precede KeyguardManager's unlocked state on OEM devices.
 * Retry visibility only, within a fixed budget; this never starts/resumes a session or command.
 * All callbacks execute on the caller's lifecycle-owned Main scope.
 */
internal class ControlUiRestore(
    private val now: () -> Long,
    private val unlocked: () -> Boolean,
    private val eligible: () -> Boolean,
    private val restore: () -> Unit,
    private val attached: () -> Boolean,
    private val record: (String, Long) -> Unit = { _, _ -> },
) {
    suspend fun run(): Boolean {
        val started = now()
        record("trigger", 0)
        var requested = false
        // Fast first traversal, then bounded backoff. No polling survives screen-off/disposal.
        for (pause in longArrayOf(0, 16, 32, 64, 128, 256, 500, 1_000)) {
            if (pause > 0) delay(pause)
            if (!eligible()) { record("ineligible", now() - started); return false }
            if (!unlocked()) { record("keyguard_wait", now() - started); continue }
            if (!requested) {
                record("unlocked", now() - started)
                record("restore_requested", now() - started)
                requested = true
            }
            restore()
            if (attached()) { record("attached", now() - started); return true }
            record("attachment_wait", now() - started)
        }
        record("timeout", now() - started)
        return false
    }
}
