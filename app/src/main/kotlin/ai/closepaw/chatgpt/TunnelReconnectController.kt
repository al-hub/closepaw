package ai.closepaw.chatgpt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One service-owned retry loop. Stop cancels both backoff and the current tunnel. */
internal class TunnelReconnectController(
    private val scope: CoroutineScope,
    private val tunnel: TunnelProvider,
    private val enabled: () -> Boolean,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onRetry: (Long, String) -> Unit = { _, _ -> },
    private val onFailure: (String) -> Unit = { _ -> },
) {
    private var job: Job? = null

    fun start(localPort: Int) {
        if (job?.isActive == true) return
        val previous = job
        job = scope.launch {
            previous?.join()
            try {
                var failures = 0
                while (enabled()) {
                    var connectedAt: Long? = null
                    val failure = try {
                        tunnel.start(localPort)
                        tunnel.status.first { status ->
                            if (status is TunnelStatus.Connected && connectedAt == null) connectedAt = nowMs()
                            status is TunnelStatus.Failed
                        } as TunnelStatus.Failed
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // Resolver/configuration/process preparation can throw before a
                        // provider publishes Failed. Clean up without leaking its message.
                        tunnel.stop()
                        TunnelStatus.Failed("tunnel_start_failed:${error.javaClass.simpleName}")
                    }
                    currentCoroutineContext().ensureActive()
                    if (!enabled()) return@launch
                    onFailure(failure.reason)
                    if (!isRetryable(failure.reason)) return@launch
                    // A briefly healthy crash loop must not reset exponential backoff.
                    if (connectedAt?.let { nowMs() - it >= 60_000 } == true) failures = 0
                    val waitMs = minOf(2_000L shl failures.coerceAtMost(5), 60_000L)
                    failures = (failures + 1).coerceAtMost(5)
                    tunnel.stop()
                    onRetry(waitMs, failure.reason)
                    delay(waitMs)
                }
            } catch (cancelled: CancellationException) {
                tunnel.stop()
                throw cancelled
            }
        }
    }

    fun stop() {
        job?.cancel()
        tunnel.stop()
    }

    private fun isRetryable(reason: String): Boolean =
        reason != "secure_tunnel_runtime_unavailable" &&
            reason != "secure_tunnel_not_configured" && reason != "cloudflared_unavailable" &&
            !reason.contains("SecurityException") && !reason.contains("IllegalArgumentException")
}
