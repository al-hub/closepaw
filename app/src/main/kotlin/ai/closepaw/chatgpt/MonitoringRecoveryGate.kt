package ai.closepaw.chatgpt

/** In-memory, single-use authority. This class does not perform or schedule recovery. */
internal class MonitoringRecoveryGate(
    private val approvedAction: String,
    private val deadlineElapsedMs: Long,
) {
    private val lock = Any()
    private var stopped = false
    private var attempted = false

    /** Stop is irreversible, including after a failed or uncertain recovery. */
    fun stop() = synchronized(lock) { stopped = true }

    /** Consume authority before dispatch, so a timeout or exception cannot replay it. */
    fun tryConsume(action: String, nowElapsedMs: Long, active: Boolean, unlocked: Boolean): Boolean =
        synchronized(lock) {
            if (!active || !unlocked || nowElapsedMs < 0 || nowElapsedMs >= deadlineElapsedMs) {
                stopped = true
            }
            if (stopped || attempted || action != approvedAction || action.isBlank()) {
                false
            } else {
                attempted = true
                true
            }
        }
}
