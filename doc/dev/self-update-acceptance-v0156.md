# Focused remote-update acceptance — v0.1.56 / code57

## Scope

User narrowed this stage to release APK -> remote self-update -> reconnection -> installed-version confirmation. Defer general device metadata/One UI/security-settings probes and new diagnostic operations. No MCP changes or extra consent toggles.

Confirmed v0.1.55 already requests USER_ACTION_NOT_REQUIRED through PackageInstaller and declares UPDATE_PACKAGES_WITHOUT_USER_ACTION; targetSdk36. Manual AppUpdater.requestInstall uses ACTION_VIEW instead. The captured manual source warning does not establish remote session behavior. Do not add flags already present or claim warnings are solved without observing the remote path.

## Minimal change

Keep existing app.self_update.check/install/status operations. Add up to eight sanitized callback records to the existing durable ledger, returned by existing status queries. Record numeric status, related package if Android supplies it, observation time and bounded confirmation routing metadata. Never collect raw Intent extras/URLs/status messages. A confirmation handler does not identify the owner of any nested warning. No security setting is disabled and no approval is clicked automatically. Existing unresolved-outcome retry guard and exact version/code confirmation remain.

## Validation and acceptance

- Callback tests cover metadata minimization and distinguishing handler from warning owner.
- Local Gradle blocked downloading its distribution (network unreachable); require GitHub Security CI before merge/release.
- Deliver a signed v0.1.56/code57 using the existing CI and original signing key; verify published APK metadata/checksum/signature.
- Query existing status and latest release, then request exactly one remote install with expected version 0.1.56 and unique request ID.
- If the connection drops, query status after reconnection; never blindly replay an unresolved install.
- If Android requires user action, report confirmed approval-pending state. Current fixed operations cannot click approvals or read arbitrary system warning screens.
- The old v0.1.55 executor cannot record the new callback fields during this bootstrap replacement. Use its state/version evidence for this hop; richer callback records apply to updates initiated by v0.1.56 or later.
- Success requires confirmed target version/code AND a responding post-update connection. CI success, accepted install request, or missing callbacks is not unattended-install proof.

API: https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)
