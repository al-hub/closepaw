package ai.closepaw.chatgpt

/** Process-local authority only: no Context, commands, credentials or screen contents.
 * A process restart drops consent. The device Activity is the only approval caller.
 * All stop paths and the final external dispatch share this monitor's lock. */
internal object SelfMonitoringAuthority {
    const val ACTION = "termux_bridge_start_once"
    data class Grant(val id: String, val sessionId: String, val epoch: Long, val durationMs: Long,
        val consentDeadline: Long, val deadline: Long? = null, val phase: String = "awaiting_consent",
        val dispatched: Boolean = false, val postCheck: String = "not_observed")
    private var current: Grant? = null
    private var gate: MonitoringRecoveryGate? = null

    @Synchronized fun request(id: String, sessionId: String, epoch: Long, durationMs: Long, now: Long): Boolean {
        if (current?.phase in setOf("awaiting_consent", "monitoring", "recovering")) return false
        if (id.isBlank() || sessionId.isBlank() || now < 0 || durationMs !in 10_000L..300_000L || now > Long.MAX_VALUE - 330_000L) return false
        current = Grant(id, sessionId, epoch, durationMs, now + 30_000L)
        gate = null
        return true
    }
    @Synchronized fun snapshot(): Grant? = current
    @Synchronized fun approve(id: String, now: Long): Boolean {
        val value = current ?: return false
        if (value.id != id || value.phase != "awaiting_consent") return false
        if (now < value.consentDeadline - 30_000L || now >= value.consentDeadline) { stop(id, "expired"); return false }
        current = value.copy(deadline = now + value.durationMs, phase = "monitoring")
        gate = MonitoringRecoveryGate(ACTION, now + value.durationMs)
        return true
    }
    @Synchronized fun stop(id: String? = null, reason: String = "stopped") {
        val value = current ?: return
        if (id != null && id != value.id) return
        gate?.stop()
        if (value.phase in setOf("awaiting_consent", "monitoring", "recovering")) current = value.copy(phase = reason)
    }
    @Synchronized fun eligible(id: String, state: MonitoringRecoveryGate.State): Boolean {
        val value = current ?: return false
        if (value.id != id || value.phase !in setOf("awaiting_consent", "monitoring", "recovering")) return false
        val deadline = value.deadline ?: value.consentDeadline
        if (!state.active || !state.unlocked || state.elapsedMs < value.consentDeadline - 30_000L || state.elapsedMs >= deadline) {
            stop(id, if (state.elapsedMs >= deadline) "expired" else "interrupted")
            return false
        }
        return true
    }
    @Synchronized fun beginRecovery(id: String): Boolean {
        val value = current ?: return false
        if (value.id != id || value.phase != "monitoring") return false
        current = value.copy(phase = "recovering")
        return true
    }
    /** Wraps the actual Android service-start call, not scheduling or a coroutine launch. */
    @Synchronized fun dispatch(id: String, state: () -> MonitoringRecoveryGate.State, action: () -> Unit): Boolean {
        val value = current ?: return false
        if (value.id != id || value.phase != "recovering") return false
        return gate?.tryDispatch(ACTION, state) {
            current = value.copy(dispatched = true)
            action()
        } == true
    }
    @Synchronized fun complete(id: String, postCheck: String) {
        val value = current ?: return
        if (value.id != id) return
        gate?.stop()
        // A post-check may describe an in-flight effect after Stop, but cannot revive it.
        current = value.copy(phase = if (value.phase == "recovering") "finished" else value.phase,
            postCheck = postCheck)
    }
}
