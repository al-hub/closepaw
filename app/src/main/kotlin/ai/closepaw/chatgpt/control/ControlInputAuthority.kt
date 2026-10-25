package ai.closepaw.chatgpt.control

import kotlinx.coroutines.sync.Mutex

/** Local and remote UI operations share one lease; local authority never starts,
 * resumes or modifies the user's remote control session. */
internal object ControlScreenLease { val mutex = Mutex() }
internal class LocalInputAuthority(private val live: () -> Boolean) {
    val session = ControlSession().also { check(it.start()) }
    fun refusal(): String? {
        if (!live()) { if (session.state != ControlSession.State.ENDED) session.end(); return "local_input_authority_revoked" }
        return session.refusal(session.id, mutation = true)
    }
    fun close() { session.end() }
}
