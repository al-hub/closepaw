package ai.closepaw.chatgpt

/**
 * In-memory, single-use authority. This class does not establish user consent,
 * schedule monitoring or grant permission to run any particular action.
 */
internal class MonitoringRecoveryGate(
    private val approvedAction: String,
    private val deadlineElapsedMs: Long,
) {
    private val lock = Any()
    private var stopped = false
    private var attempted = false

    /** Once stop returns, no new dispatch can start through this gate. */
    fun stop() = synchronized(lock) { stopped = true }

    /**
     * Synchronously initiate a previously approved, bounded action while holding
     * the same lock used by stop. The caller must supply fresh lifecycle state and
     * clock values at the dispatch boundary. Once dispatch starts, stop cannot
     * undo its effects. An exception still consumes the authority; no replay.
     *
     * The callback must only initiate a bounded non-blocking action; it must not
     * wait for a result, invoke arbitrary commands, or call back into this gate.
     */
    fun tryDispatch(
        action: String, nowElapsedMs: Long, active: Boolean, unlocked: Boolean,
        dispatch: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (!active || !unlocked || nowElapsedMs < 0 || nowElapsedMs >= deadlineElapsedMs) {
            stopped = true
        }
        if (stopped || attempted || action != approvedAction || action.isBlank()) {
            false
        } else {
            attempted = true
            dispatch()
            true
        }
    }
}
