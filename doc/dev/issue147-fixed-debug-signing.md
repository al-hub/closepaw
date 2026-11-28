# Issue #147 — fixed identity for separately installed Debug candidate

The candidate package is `ai.closepaw.capturecandidate`. The production
package `ai.closepaw`, its signing key, data and release workflow are out of
scope. This process is deliberately **not an automatic migration**.

## One-time security-owner setup

1. Create **one** dedicated long-lived QA signing keystore in a protected
   location; do not reuse any release key, debug default key, or another
   app's signature. Keep an independently protected offline backup. Do not
   commit the keystore, key passwords, or their base64 form.
2. Record the dedicated signing certificate's SHA-256 using Android
   `apksigner verify --print-certs` on a local APK signed by that exact
   keystore, or via `keytool -list -v` against the keystore. Remove
   separators when setting the fingerprint. The certificate is public;
   the keystore and its passwords are not.
3. In GitHub repository **Settings > Secrets and variables > Actions**, add
   these protected **repository secrets**:
   - `CLOSEPAW_QA_DEBUG_KEYSTORE_BASE64`: base64-encoded raw keystore bytes
   - `CLOSEPAW_QA_DEBUG_KEYSTORE_PASSWORD`: keystore password
   - `CLOSEPAW_QA_DEBUG_KEY_ALIAS`: candidate-only key alias
   - `CLOSEPAW_QA_DEBUG_KEY_PASSWORD`: key password
4. Add repository **variable** `CLOSEPAW_QA_DEBUG_CERT_SHA256`: the exact
   64-character hex certificate SHA-256. Never publish secret values in an
   issue, chat, workflow summary or artifact.
5. Make the signer available to the trusted owner-maintained candidate
   branch's CI and protect secret access using GitHub permissions. Review
   workflow edits before allowing them to use these secrets.

The workflow restores the keystore inside the ephemeral runner's
`$RUNNER_TEMP` with private file permissions and checks the **signed APK**
certificate against the pinned fingerprint. Missing secrets or a different
certificate **fail closed**. This is a setup blocker, not a reason to fall
back to a randomly generated signing key. Only the
`-PclosepawCaptureCandidate=true` debug variant reads
`QA_DEBUG_KEYSTORE_*`. Release and ordinary debug builds are unchanged.

## Same-signature candidate update

1. Require passing Security and Premium UX UI CI for the **exact** PR
   candidate HEAD. Confirm the candidate APK source manifest package,
   version, certificate SHA-256 and artifact SHA-256; the artifact must
   match the checked commit.
2. Verify the *existing installed candidate's* signing identity by a
   supported, read-only device package inspection. Do not assume it matches
   merely because both versions are named Debug.
3. **If and only if identical:** update `ai.closepaw.capturecandidate`
   once via the already authorized Termux wireless Android debug path
   (`adb install -r` as appropriate). Confirm installed package/version
   and that Debug app data and Android approvals survived the replacement.
   Do not use a PC USB ADB path.
4. A mismatch such as `INSTALL_FAILED_UPDATE_INCOMPATIBLE` is an expected
   stop: **never auto-uninstall, change release signing, or bypass the
   package manager**. Document the Debug-only one-time migration and its
   costs first (loss of candidate data, accessibility enablement, overlay
   settings, screen-sharing consent and Connect setting), and seek explicit
   approval before any removal. Keep `ai.closepaw` installed.
5. Request Android access/sharing only immediately before running the
   consented physical-key test. Restore temporary permissions and end the
   test control session afterward. Report unknown touches separately.

## Acceptance versus preparation

A candidate APK that compiles or passes a read-only capture contract is
**not** a physical input PASS. #147's 6-key target is
`ㄱ→ㅏ→Space→ㄴ→ㅏ→Space`, exact `가 나 ` (trailing space) and caret 4/4.
The canonical 29-character benchmark is
`오늘 회의는 zoom으로 3시에 시작, 자료는 2개.`.
Neither should be counted successful without independent on-device
evidence; #147 remains OPEN and #286 stays Draft until required acceptance.

**Current known blocker (2026-11-22):** the candidate signing secrets and
pinned variable are absent in GitHub Actions run 45015285220, so the
protected signing job correctly failed before building the candidate.
A fresh signed APK, update compatibility, and Galaxy physical-key outcome
are all unverified.
