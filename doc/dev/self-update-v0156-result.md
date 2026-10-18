# v0.1.56 remote update result

2026-10-18 Galaxy acceptance from installed v0.1.55/code56 to v0.1.56/code57.

- User explicitly requested a version-only signed release and direct remote installation test; all proposed diagnostics/callback additions were deferred. PR #101 final diff only updates VERSION_NAME and VERSION_CODE.
- PR Security CI 39122847299 and main Security CI 39123153623 succeeded. Signed Release 39123502932 succeeded, including previous-release signing certificate comparison and publication.
- Published APK asset: 116397169 bytes, GitHub SHA-256 digest `bc77f123750939ed43e6af9fa300406d5dc340e4b24118991cbf88c18cfe2b97`. Checksum asset published. Local binary re-verification was not performed; the device downloadAndVerify path validates checksum/package/version/signing identity before installation.
- Device release check returned available_version 0.1.56. Submitted exactly one app.self_update.install with expected_version 0.1.56.
- Task returned installing with target code57 while the old code56 was still running. This was treated only as accepted installation, not success.
- The next status query timed out. No install retry was sent. Subsequent get_status confirmed ClosePaw reachable and responding at v0.1.56/code57.
- app.self_update.status then returned state=installed, target_version=current_version=0.1.56 and target_code=current_code=57; remote consent and install-source permission remained enabled.
- No pending-user-action result was observed and the assistant did not request an extra user approval or manual install during this attempt. Screen/approval interactions are not independently recorded, so this proves remote initiation, installed target version and post-update connectivity; it does not prove every future update is warning-free or that a transient warning could never have appeared.

Keep the existing check -> one install -> reconnect -> status flow. Never replay unresolved installs blindly. Future Android/Samsung security approvals remain possible. No security settings were disabled and no warnings were clicked through by automation.
