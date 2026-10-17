package ai.closepaw.tailscale

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** Bounded network recovery only. The caller retains ownership of the original task. */
internal class TailscaleRecovery(
    private val platform: TailscalePlatform,
    private val enabled: () -> Boolean,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val record: (TailscaleOutcome) -> Unit = {},
) {
    private val lock = Mutex()
    private var lastConnectAt: Long? = null

    suspend fun status(target: PrivateTarget? = null): TailscaleOutcome {
        val local = platform.observe()
        val probe = target?.let { platform.probe(it, 2000) }
        return TailscaleOutcome(
            probe?.let { if (it.reachable) "target_reachable" else it.reason } ?: localReason(local) ?:
                if (local.overlayAddressPresent) "overlay_link_observed" else "connection_not_observed",
            local, probe, success = probe?.reachable == true,
        )
    }

    suspend fun ensure(target: PrivateTarget, timeoutMs: Int): TailscaleOutcome {
        require(timeoutMs in 2000..20000)
        if (!lock.tryLock()) return TailscaleOutcome("recovery_in_progress", TailscaleLocalState(observationError = "not_observed"))
        var last = TailscaleOutcome("connection_unconfirmed", TailscaleLocalState(observationError = "not_observed"))
        try {
            val result = withTimeoutOrNull(timeoutMs.toLong()) {
                if (!enabled()) return@withTimeoutOrNull last.copy(state = "control_disabled")
                last = status(target)
                if (last.probe?.reachable == true) return@withTimeoutOrNull last.copy(state = "target_already_reachable", success = true)
                if (last.probe?.reason in setOf("target_port_refused", "target_outside_private_network", "probe_permission_denied")) return@withTimeoutOrNull last
                localReason(last.local)?.let { return@withTimeoutOrNull last.copy(state = it) }
                if (last.local.vpnPresent) {
                    // Android withholds the VPN owner UID from other apps. A CGNAT/ULA
                    // address is insufficient evidence to replace an already active VPN.
                    return@withTimeoutOrNull last.copy(state = if (last.local.overlayAddressPresent)
                        "vpn_active_target_unreachable" else "vpn_conflict_or_unidentified")
                }
                val remaining = lastConnectAt?.let { 30000 - (nowMs() - it) } ?: 0L
                if (remaining > 0) return@withTimeoutOrNull last.copy(state = "connection_cooldown", cooldownRemainingMs = remaining)
                if (!enabled()) return@withTimeoutOrNull last.copy(state = "control_disabled")
                lastConnectAt = nowMs()
                runCatching { record(last.copy(state = "connect_requesting")) }
                val dispatched = platform.connect()
                if (!dispatched.accepted) return@withTimeoutOrNull last.copy(state = dispatched.reason ?: "connect_request_failed")
                last = last.copy(connectRequested = true)
                runCatching { record(last.copy(state = "connect_requested")) }
                while (true) {
                    if (!enabled()) return@withTimeoutOrNull last.copy(state = "control_disabled")
                    val observed = status(target)
                    last = observed.copy(connectRequested = true)
                    if (last.probe?.reachable == true) return@withTimeoutOrNull last.copy(state = "target_reachable", success = true)
                    if (last.probe?.reason in setOf("target_port_refused", "target_outside_private_network", "probe_permission_denied")) return@withTimeoutOrNull last
                    delay(1000)
                }
                @Suppress("UNREACHABLE_CODE") last
            } ?: last.copy(state = if (last.connectRequested) "connection_unconfirmed" else "diagnosis_timeout", success = false)
            runCatching { record(result) }
            return result
        } finally {
            lock.unlock()
        }
    }

    private fun localReason(local: TailscaleLocalState): String? = when {
        local.observationError != null -> local.observationError
        !local.installed -> "app_not_installed"
        !local.appEnabled -> "app_disabled"
        !local.connectSupported -> "connect_not_supported"
        !local.controlPermissionGranted -> "connect_permission_missing"
        else -> null
    }
}
