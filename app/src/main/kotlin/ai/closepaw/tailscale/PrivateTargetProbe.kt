package ai.closepaw.tailscale

import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Resolves once, validates every address, and probes only the selected private TCP endpoint. */
internal class PrivateTargetProbe(
    private val resolve: suspend (String) -> List<InetAddress>,
    private val connect: suspend (InetAddress, Int, Int) -> TargetProbe,
) {
    suspend fun probe(target: PrivateTarget, timeoutMs: Int): TargetProbe = try {
        withTimeoutOrNull(timeoutMs.toLong()) {
            val addresses = resolve(target.host)
            if (addresses.isEmpty()) return@withTimeoutOrNull TargetProbe("target_dns_failed")
            if (addresses.any { !PrivateTargetPolicy.isPrivate(it) }) return@withTimeoutOrNull TargetProbe("target_outside_private_network")
            for (address in addresses.distinct().take(4)) {
                val result = connect(address, target.port, minOf(timeoutMs, 700))
                if (result.reachable || result.reason == "target_port_refused") return@withTimeoutOrNull result
            }
            TargetProbe("target_unreachable")
        } ?: TargetProbe("target_probe_timeout")
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: UnknownHostException) { TargetProbe("target_dns_failed")
    } catch (_: SecurityException) { TargetProbe("probe_permission_denied")
    } catch (_: Exception) { TargetProbe("target_unreachable") }
}
