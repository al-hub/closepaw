# Update diagnostics — v0.1.56 / code57

## Confirmed from v0.1.55 code

- Manual update calls AppUpdater.requestInstall: FileProvider APK + ACTION_VIEW.
- Remote update calls AndroidSelfUpdater.start: PackageInstaller session with USER_ACTION_NOT_REQUIRED.
- UPDATE_PACKAGES_WITHOUT_USER_ACTION is already declared; targetSdk is 36.
- Therefore adding that flag/permission is not a new remedy. A warning from the older manual path does not establish how the remote session behaves.
- Device query confirmed v0.1.55/code56 and both remote-update consent and install-source permission enabled. No remote replacement has been tested. Warning text captured by the user states untrusted source, not a signature error or malware verdict; the emitting component is unproven.

## Added evidence

`app.self_update.diagnostics` is a read-only empty-input operation, discovered through the existing fixed MCP envelope. It does not install, launch screens, alter settings, or require Termux.

Returns API/release, manufacturer/model, target SDK, own install permission, update-without-user-action permission, notification availability, own installer-of-record and resolvable manual APK handler. One UI version, protected security settings and actual warning owner remain explicitly unknown. Resolver results are routing evidence only. No device identifiers, APK paths, URLs, raw status messages, Intent extras, or screenshot text are returned.

For a matching pending remote installation only, record up to eight PackageInstaller callback events: numeric status, related package (when supplied by Android), timestamp, and bounded allowlisted confirmation action/component/resolved package. State remains governed by existing ledger semantics; an approval callback is never treated as installation success. Diagnostics read ledger evidence without reconciling or writing it.

## Validation and device acceptance

- Added operation tests for read-only discovery, rejecting install input, preserving uncertainty and no install/status invocation.
- Added callback tests for metadata minimization and distinguishing confirmation handler from warning owner.
- Local Gradle blocked before compilation: distribution host network unreachable. GitHub Security CI must validate Android tests and build; no local test-pass claim.
- No security setting is disabled, and no warning is automatically clicked.
- After signed release delivery, use the installed v0.1.55 remote path once with unique request ID and expected version 0.1.56. Do not blindly replay after a disconnect or unknown outcome. On required approval, retain the native request and explain it; a user action may be unavoidable for this diagnostic bootstrap update.
- After reconnect, query installed target version/code and the new diagnostics operation. The old process cannot produce the new callback fields during the bootstrap replacement; first-hop missing callbacks are expected, not proof of no warning. New callback evidence requires an update initiated by v0.1.56 or later.
- Finish feasibility assessment only from observed device behavior and documented limits. A green CI, permission flag, successful resolver, or completed download alone does not prove unattended installation or warning removal.

Official API: https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)
