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
        // Backoff could miss a late OEM unlock for up to 1s. Keep the same bounded
        // visibility-only budget, but check cheap keyguard state once per frame.
        // No polling survives screen-off/disposal and no session is resumed here.
        while (true) {
            if (!eligible()) { record("ineligible", now() - started); return false }
            if (!unlocked()) {
                record("keyguard_wait", now() - started)
            } else {
                if (!requested) {
                    record("unlocked", now() - started)
                    record("restore_requested", now() - started)
                    requested = true
                }
                restore()
                if (attached()) { record("attached", now() - started); return true }
                record("attachment_wait", now() - started)
            }
            val remaining = 2_000L - (now() - started)
            if (remaining <= 0) break
            delay(minOf(16L, remaining))
        }
        record("timeout", now() - started)
        return false
    }
}
