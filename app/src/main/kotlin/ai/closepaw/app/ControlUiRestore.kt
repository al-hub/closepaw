package ai.closepaw.app

import kotlinx.coroutines.delay

private const val VISIBILITY_RESTORE_BUDGET_MS = 8_000L

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
        // Screen-on can precede a slow keyguard dismissal by several seconds.
        // Poll each frame for the first second, then every 50ms within a fixed
        // visibility-only budget. Screen-off cancels the job; a stale/ended
        // session is rejected by eligible() and never resumed.
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
            val elapsed = now() - started
            val remaining = VISIBILITY_RESTORE_BUDGET_MS - elapsed
            if (remaining <= 0) break
            delay(minOf(if (elapsed < 1_000L) 16L else 50L, remaining))
        }
        record("timeout", now() - started)
        return false
    }
}
