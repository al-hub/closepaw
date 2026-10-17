package ai.closepaw.tailscale

internal data class PrivateTarget(val host: String, val port: Int) {
    init { require(PrivateTargetPolicy.validHost(host) && port in 1..65535) { "Invalid private-network host or port" } }
}

internal data class TailscaleLocalState(
    val installed: Boolean = true,
    val appEnabled: Boolean = true,
    val connectSupported: Boolean = true,
    val controlPermissionGranted: Boolean = true,
    val vpnPresent: Boolean = false,
    // This is link evidence, not proof of ownership, login, routing or backend health.
    val overlayAddressPresent: Boolean = false,
    val versionName: String? = null,
    val observationError: String? = null,
)

internal data class TargetProbe(val reason: String) {
    val reachable: Boolean get() = reason == "reachable"
}

internal data class ConnectDispatch(val accepted: Boolean, val reason: String? = null)

/** No shell execution, credential access, app installation or disconnect capability. */
internal interface TailscalePlatform {
    suspend fun observe(): TailscaleLocalState
    suspend fun probe(target: PrivateTarget, timeoutMs: Int): TargetProbe
    suspend fun connect(): ConnectDispatch
}

internal data class TailscaleOutcome(
    val state: String,
    val local: TailscaleLocalState,
    val probe: TargetProbe? = null,
    val success: Boolean = false,
    val connectRequested: Boolean = false,
    val cooldownRemainingMs: Long = 0,
)
