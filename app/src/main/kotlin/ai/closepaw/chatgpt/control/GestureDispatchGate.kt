package ai.closepaw.chatgpt.control

/** Evaluated in the continuation immediately before OS dispatch, after all Binder guards. */
internal object GestureDispatchGate {
    fun refusal(session: ControlSession?, expectedEpoch: Long, active: Boolean): String? = when {
        !active -> "gesture_cancelled_before_dispatch"
        session == null || session.epoch != expectedEpoch -> "control_session_changed_observe_again"
        else -> session.refusal(session.id, mutation = true)
    }
}
