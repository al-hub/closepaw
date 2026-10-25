package ai.closepaw.ui.settings

import ai.closepaw.termux.NeedsSetupReason
import ai.closepaw.termux.TermuxBridgeStatus

/**
 * Pure mapper: collapse the live Termux bridge state, the runtime permission disposition,
 * and the user's enable pref into the display-only [ToolStatusUi] the unified tool card
 * renders. No Compose dependency — covered by JVM unit tests.
 *
 * Label words MUST stay exactly `"Ready" / "Needs Setup" / "Setting up…" / "Not Installed"
 * / "Disabled" / "Offline"` — `SettingsTermuxRowTest` matches on them.
 */
internal fun termuxStatusUi(
    state: TermuxBridgeStatus,
    permissionDisposition: RunCommandPermissionDisposition?,
    enabledPref: Boolean,
): ToolStatusUi {
    val displayed = if (enabledPref) state else TermuxBridgeStatus.Disabled
    return ToolStatusUi(
        label = displayed.label(),
        subtitle = displayed.subtitleFor(permissionDisposition),
        tone = displayed.tone(),
        showSpinner = displayed is TermuxBridgeStatus.SetupInProgress,
    )
}

private fun TermuxBridgeStatus.label(): String = when (this) {
    TermuxBridgeStatus.NotInstalled -> "Not Installed"
    is TermuxBridgeStatus.NeedsSetup ->
        if (reason == NeedsSetupReason.TERMUX_NOT_RUNNING || reason == NeedsSetupReason.HEALTH_TIMEOUT || reason == NeedsSetupReason.BRIDGE_OFFLINE_NO_RUN_COMMAND || reason == NeedsSetupReason.TERMUX_START_RESTRICTED) "Offline" else "Needs Setup"
    TermuxBridgeStatus.SetupInProgress -> "Setting up…"
    TermuxBridgeStatus.Ready -> "Ready"
    TermuxBridgeStatus.Disabled -> "Disabled"
}

private fun TermuxBridgeStatus.tone(): ToolStatusTone = when (this) {
    TermuxBridgeStatus.Ready -> ToolStatusTone.Positive
    TermuxBridgeStatus.NotInstalled,
    is TermuxBridgeStatus.NeedsSetup -> ToolStatusTone.Warning
    TermuxBridgeStatus.SetupInProgress,
    TermuxBridgeStatus.Disabled -> ToolStatusTone.Neutral
}

private fun TermuxBridgeStatus.subtitle(): String = when (this) {
    TermuxBridgeStatus.NotInstalled -> "Install Termux from F-Droid"
    is TermuxBridgeStatus.NeedsSetup -> reason.toDisplayText()
    TermuxBridgeStatus.SetupInProgress -> "This may take a minute"
    TermuxBridgeStatus.Ready -> "Termux bridge running — tap to restart"
    TermuxBridgeStatus.Disabled -> "Toggle to enable"
}

/**
 * PERMISSION_MISSING is the only reason whose subtitle depends on the runtime disposition.
 * `OpenAppSettings` means the user picked "Don't ask again", so the system dialog no longer
 * surfaces — point them to App Settings instead of "tap to grant".
 */
private fun TermuxBridgeStatus.subtitleFor(
    permissionDisposition: RunCommandPermissionDisposition?,
): String {
    if (this is TermuxBridgeStatus.NeedsSetup &&
        reason == NeedsSetupReason.PERMISSION_MISSING
    ) {
        return when (permissionDisposition) {
            RunCommandPermissionDisposition.OpenAppSettings ->
                "Permission permanently denied. Tap to open App Settings → Permissions."
            RunCommandPermissionDisposition.Request,
            RunCommandPermissionDisposition.Granted,
            null -> "Tap to grant RUN_COMMAND permission to Termux."
        }
    }
    return subtitle()
}

private fun NeedsSetupReason.toDisplayText(): String = when (this) {
    NeedsSetupReason.PERMISSION_MISSING -> "Tap to grant RUN_COMMAND permission to Termux."
    NeedsSetupReason.ALLOW_EXTERNAL_APPS_MISSING ->
        "Allow external apps is disabled in Termux. Enable it, then tap setup."
    NeedsSetupReason.TERMUX_NOT_RUNNING ->
        "Local Bridge is offline; RUN_COMMAND recovery is unavailable. Open Termux and run ~/.termux/boot/10-closepaw-bridge. Existing pairing is retained."
    NeedsSetupReason.BRIDGE_OFFLINE_NO_RUN_COMMAND ->
        "Local Bridge is unreachable and this Termux build has no RUN_COMMAND service. Process state is unknown. Run the recovery command once in Termux; existing pairing is retained."
    NeedsSetupReason.TERMUX_START_RESTRICTED ->
        "Android blocked the Termux service start. Open Termux and run the existing recovery hook; pairing is retained."
    NeedsSetupReason.TERMUX_RUN_COMMAND_UNAVAILABLE ->
        "RUN_COMMAND is unavailable. Use the one-time Local Bridge bootstrap below."
    NeedsSetupReason.PACKAGES_MISSING -> "Missing packages — tap to install python/git/ripgrep"
    NeedsSetupReason.BRIDGE_OUTDATED -> "Bridge daemon out of date — tap to update"
    NeedsSetupReason.AUTH_MISMATCH ->
        "Local Bridge pairing token does not match. Repeat the pairing steps below."
    NeedsSetupReason.BRIDGE_HEALTH_UNVERIFIED ->
        "A localhost service responded but bridge identity is unverified. Inspect port 18422; do not change pairing."
    NeedsSetupReason.BRIDGE_RESPONSE_TIMEOUT ->
        "Local Bridge response timed out. Process state is unknown; do not replay commands."
    NeedsSetupReason.HEALTH_TIMEOUT -> "Local Bridge is offline — retry recovery. Existing pairing is retained."
    NeedsSetupReason.TERMUX_TIMEOUT -> "Termux command timed out — open Termux once and retry"
    NeedsSetupReason.PORT_IN_USE -> "Port 18422 in use by another process"
    NeedsSetupReason.UNKNOWN -> "Setup error — tap to retry"
}
