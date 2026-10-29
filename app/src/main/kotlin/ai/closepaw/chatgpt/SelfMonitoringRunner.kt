package ai.closepaw.chatgpt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** One lifecycle, no retry of recovery. Probes are passive and post-check is a new request. */
internal class SelfMonitoringRunner(
    private val state: () -> MonitoringRecoveryGate.State,
    private val probe: suspend () -> String,
    private val recover: suspend ((() -> Unit) -> Boolean) -> Unit,
) {
    suspend fun run(id: String) {
        try {
            while (SelfMonitoringAuthority.eligible(id, state())) {
                val grant = SelfMonitoringAuthority.snapshot() ?: return
                if (grant.phase == "awaiting_consent") { delay(250); continue }
                when (probe()) {
                    "ready" -> delay(2_000)
                    "offline_recoverable" -> {
                        if (!SelfMonitoringAuthority.eligible(id, state()) || !SelfMonitoringAuthority.beginRecovery(id)) return
                        var unknown = false
                        try {
                            recover { dispatch -> SelfMonitoringAuthority.dispatch(id, state, dispatch) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { unknown = true }
                        val post = if (SelfMonitoringAuthority.eligible(id, state())) probe() else "not_observed"
                        SelfMonitoringAuthority.complete(id, if (unknown) "dispatch_unknown_post_$post" else post)
                        return
                    }
                    else -> { SelfMonitoringAuthority.stop(id, "blocked"); return }
                }
            }
        } catch (cancelled: CancellationException) {
            SelfMonitoringAuthority.stop(id, "interrupted")
            throw cancelled
        } catch (_: Exception) {
            SelfMonitoringAuthority.stop(id, "unknown")
        } finally {
            SelfMonitoringAuthority.stop(id)
        }
    }
}
