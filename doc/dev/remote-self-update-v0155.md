# Remote self-update preparation — v0.1.55 / code56

## Status

## v0.1.55/code56 remote self-update APK delivered — device acceptance pending

- User explicitly approved public source publication and release delivery in this conversation. [PR #100](https://github.com/al-hub/closepaw/pull/100) merged; immutable APK source/tag target `19593ea0556be272a8eadffe5835de976539e031`.
- PR [Security CI](https://github.com/al-hub/closepaw/actions/runs/39119481080), main [Security CI](https://github.com/al-hub/closepaw/actions/runs/39119872501), and [Signed Release](https://github.com/al-hub/closepaw/actions/runs/39120247413) succeeded.
- Downloaded published [v0.1.55 APK](https://github.com/al-hub/closepaw/releases/download/v0.1.55/closepaw-aa-bridge.apk): 116,397,169 bytes; package `ai.closepaw`, version `0.1.55`, code56. SHA-256 `d9048083a8cbef2ca8f74fca3145472ad15791c50ad80db2d5451fcff6c09f56` matches published checksum and GitHub asset digest. Independently verified APK v2 signature AND whole-file chunk digest, original certificate `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`, both native runtimes and new install permission.
- Android report: **2,493 tests, zero failures/errors/skips**, including16 new policy/ledger/operation tests. Python bridge55+8 also passed. These are not an installed Android PackageInstaller/approval test.
- Manual update buttons remain. Added fixed-envelope operations `app.self_update.check`, `app.self_update.install`, `app.self_update.status`; no MCP tool re-registration. Separate local remote-update consent defaults OFF.
- **First install manually** using Settings → Software Update. Capture the actual security warning before deciding the remedy. Then enable “Allow updates requested through ChatGPT” and allow installation from ClosePaw if required.
- After installation, discover operations and query status to verify version56, local consent and install-source permission. This establishes preparation only. Actual unattended replacement/reconnect requires a **later higher version code** and separate device acceptance. Do not claim full automation, warning removal or successful device installation based on this release.
- System-required approval is reported; Play Protect/Samsung checks are not bypassed. Unknown installation outcomes block automatic resubmission; query target installed version after reconnection. Existing pairing/settings and manual workflow remain.
- The user's plan: capture the warning during this manual update, then verify readiness before attempting a later remote update. Broader Gemini/P2 acceptance remains unchanged.


Implementation prepared locally. The user explicitly approved publication to the existing public al-hub/closepaw repository and release APK delivery on 2026-10-18. Android CI and signed delivery are now verified above; installed-device acceptance remains pending; no unattended-update claim is made.

## Behavior

- Existing manual Check for updates / Download and install remains available.
- New fixed-envelope operations: app.self_update.check, app.self_update.install, app.self_update.status. No new MCP tool definition or plugin re-registration.
- Remote install requires separate device-owned consent in Software Update, default OFF, and Android install-source permission. It requires expected_version from release check; arbitrary URLs/packages/commands are not accepted.
- Shared download verifies release checksum, package, exact version, increasing version code, and original installed signer set. Signer rotation deliberately fails closed for now.
- PackageInstaller session requests USER_ACTION_NOT_REQUIRED with UPDATE_PACKAGES_WITHOUT_USER_ACTION permission; this is a conditional request, not a guarantee.
- System approval produces a notification and can be opened from the update page while its exact PendingIntent is available. Notification permission/channel settings can affect delivery. Pending approval may need the existing notification after process restart.
- Private durable ledger records target version/code, task and session IDs. An unresolved session blocks another remote install. A status query confirms installed only when the running build matches target version AND code. MCP task success means installer request returned; it does not mean installed.
- Existing MY_PACKAGE_REPLACED reconnect path is retained; ledger reconciliation failure cannot prevent restore. Reconnection and target version must be independently checked by the caller.
- Play Protect/Samsung warnings are never disabled or clicked through. Exact warning diagnosis remains pending.

## Validation prepared

16 new JVM tests cover identity/version/downgrade rejection, durable state recreation/corruption, installed evidence, operation schema/readiness, submission semantics and status-only behavior. No claim they passed yet.

- git diff --check passes locally.
- Local Gradle cannot download its distribution (network unreachable); no Android SDK/build verification has completed.
- Local Python bridge regressions unavailable because pytest is absent. Use existing Security CI once publication is authorized.

## Delivery gates

1. Review public diff, approve push to al-hub/closepaw, open PR; run existing Security CI.
2. Correct failures, validate main unchanged, merge after green CI. Existing signed-release flow must use original signing key and immutable new v0.1.55 tag.
3. Verify signed APK/version56/checksum/signer/native runtimes and report download URL only after published.
4. First install this feature APK manually; old installed v0.1.54 cannot execute the new operation. Enable local remote-update permission once.
5. Test a later version via check -> install with unique request_id -> reconnect -> status. Verify system approval and warning cases on the Galaxy. A release build is not proof of unattended installation.

No pairing/token/Tailscale/KUM settings are changed. No private keys or device-specific data are included.
