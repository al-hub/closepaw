package ai.closepaw.termux

sealed class TermuxBridgeStatus {
    /** Termux is not installed on the device. */
    object NotInstalled : TermuxBridgeStatus()

    /** Termux is installed but needs setup before use. */
    data class NeedsSetup(val reason: NeedsSetupReason) : TermuxBridgeStatus()

    /** Termux bridge setup is currently running. */
    object SetupInProgress : TermuxBridgeStatus()

    /** Termux bridge is ready to accept shell requests. */
    object Ready : TermuxBridgeStatus()

    /** Termux shell is disabled by user settings. */
    object Disabled : TermuxBridgeStatus()
}

enum class NeedsSetupReason {
    PERMISSION_MISSING,
    ALLOW_EXTERNAL_APPS_MISSING,
    TERMUX_NOT_RUNNING,
    BRIDGE_OFFLINE_NO_RUN_COMMAND,
    TERMUX_START_RESTRICTED,
    TERMUX_RUN_COMMAND_UNAVAILABLE,
    PACKAGES_MISSING,
    BRIDGE_OUTDATED,
    AUTH_MISMATCH,
    BRIDGE_HEALTH_UNVERIFIED,
    BRIDGE_RESPONSE_TIMEOUT,
    HEALTH_TIMEOUT,
    TERMUX_TIMEOUT,
    PORT_IN_USE,
    UNKNOWN
}

data class TermuxCapabilitySnapshot(
    val available: Boolean,
    val enabled: Boolean,
    val status: TermuxBridgeStatus
) {
    companion object {
        val Unavailable = TermuxCapabilitySnapshot(
            available = false,
            enabled = false,
            status = TermuxBridgeStatus.Disabled
        )
    }
}
