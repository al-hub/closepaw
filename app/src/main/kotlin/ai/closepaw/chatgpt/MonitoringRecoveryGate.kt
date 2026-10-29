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

    internal data class State(val elapsedMs: Long, val active: Boolean, val unlocked: Boolean)

    /**
     * Synchronously initiate a previously approved, bounded action while holding
     * the same lock used by stop. The state callback samples lifecycle and elapsed time inside the lock
     * immediately before dispatch; errors fail closed. Once dispatch starts, stop cannot
     * undo its effects. An exception still consumes the authority; no replay.
     *
     * The callback must only initiate a bounded non-blocking action; it must not
     * wait for a result, invoke arbitrary commands, or call back into this gate.
     */
    fun tryDispatch(
        action: String, state: () -> State, dispatch: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (stopped || attempted || action != approvedAction || action.isBlank()) return@synchronized false
        val snapshot = try { state() } catch (_: Exception) {
            stopped = true
            return@synchronized false
        }
        if (!snapshot.active || !snapshot.unlocked || snapshot.elapsedMs < 0 || snapshot.elapsedMs >= deadlineElapsedMs) {
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
