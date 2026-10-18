# Generic Android control v0.1.57 — design and acceptance

Status: **SIGNED DELIVERY AND REMOTE UPDATE VERIFIED; GENERIC DEVICE ACCEPTANCE PENDING**.
Started from main6662e95f / installed v0.1.56/code57; current installed version is v0.1.57/code58.
No open PR existed at start. The baseline updater reported code57 with consent and install-source permission true; the latest evidence below supersedes that baseline.

ChatGPT chooses each bounded action from returned screen data. ClosePaw supplies no extra LLM account or planner.
Three operations share the fixed MCP envelope: `android.screen.observe`, `android.apps.list`, `android.ui.action`.
Generic consent is device-local, separate, default OFF. Existing pairing, endpoint, settings and Gemini fast path remain.

One observation owns its node IDs, expires after60 seconds, and is consumed before an attempted action.
The complete bounded tree fingerprint, app and window are rechecked immediately before execution.
Unknown outcomes never replay. Existing durable request/task ledger and screen mutex cover generic and dedicated paths.
A single operation performs one action and returns one new observation; no unlimited macro.
Each call has a10-second bound; a chain stops after20 actions,180 seconds or3 unchanged screens. Output has at most50 nodes,100 characters per text field and24k serialized node characters.
Truncated observations are read-only. No coordinate fallback or model-name-specific branch is introduced.
Native accessibility is sufficient for the first adapter; optional imagery/OCR is explicitly absent.

Existing classifier BLOCKED floor is preserved. Unknown/CAUTIOUS apps cannot be manipulated by this first adapter.
Home launcher and verified Gemini host are recognized by runtime identity. Password/auth/permission/installer/own settings
boundaries fail closed. Screen text is untrusted data. Raw device screen, app list, questions and responses stay out of public records.

Regression tests cover stale/wrong/expired/consumed observation IDs, consent revocation, truncation,
missing node, nonempty draft, protected screen and unknown click outcomes.
Local Android Gradle is unavailable because services.gradle.org is unreachable; use PR/main CI.

Pending: local generic opt-in, actual Gemini model choice/selection and re-observation, one dedicated question/new reply,
second-app navigation/scroll and Voice latency. P2 remains ACTIVE. CI, signed delivery, public download checks and remote replacement are complete.

## Local checkpoint

Implemented native bounded accessibility adapter and registry wiring, default-OFF settings switch,
Android14+ secure-window probe (buffer immediately closed), password/sensitive-node denial, active local-agent rejection,
launcher visibility, exact selected package launch and empty-field-only native typing. The adapter uses existing
AccessibilityPlatform launch/system executors and the same native node actions as the existing guarded Gemini adapter.
It rejects a busy shared screen lock immediately; it never waits behind a long Gemini collector and then surprises the user.
Generic mutation on Gemini is refused if the latest dedicated question ledger outcome is unknown.
The common engine contains no model names or app-specific model selection. No user target model has been specified yet.

Validation actually completed: `git diff --check`; Python Termux bridge55 and ChatGPT bridge8 tests passed.
19 Kotlin tests are written, not run:15 observation/target/effect cases and4 registry/mutex/dedup/ledger cases.
The initial RED baseline commit exists locally but was not executed: Gradle distribution download is unreachable.
This is TDD test-first source preparation, not proven RED/GREEN evidence. No build, lint or Android success claim.

Automatic approval review rejected `git push -u origin feat/generic-android-control`, citing insufficient explicit
user authorization to export new source/tests to an external repository. This is a publication authorization blocker,
not a broken GitHub connection. Do not route around the rejection through connector file writes or an alternate push.
Once the user explicitly approves public source push, PR/merge and signed APK publication to al-hub/closepaw,
resume the existing branch: push, open PR, run/fix full tests and lint/build, inspect final diff, merge after GREEN,
follow current main Security CI and existing Signed Release, download and verify package/version/code58/checksum/signer
and both runtimes/source tag, then use discovered self-update check/install/status exactly once and verify reconnect.
Version0.1.57/code58 is a local candidate only; recheck remote version conflicts before publishing.
The existing v0.1.56 installed state and permissions do not verify replacement by the new candidate.

After a verified update, ask the user to enable Settings → ChatGPT Voice → Allow ChatGPT Android screen control
and leave an unlocked ordinary screen visible. Discover generic schemas and verify readiness. Enumerate actual Gemini
model choices, use the user's specified available model (ask if unspecified), re-observe, then submit one harmless
dedicated question and inspect its new reply. Use the same generic contract on a second ordinary app. Record only
metadata/public synthetic fixtures; keep real screens/app list/question/response content out of GitHub.
Conservative initial limitations: no screenshot/OCR returned, no coordinate/swipe fallback, no Android12/13 generic
operation without a secure-window signal, no CAUTIOUS unknown apps, no automation of own/permission/auth settings.
A generic action is one atomic attempt; running native effects cannot be undone/cancelled by cancel_task.

## Approval resolved / final code review

The user explicitly approved public source push, PR merge and signed APK publication at2026-10-18 21:24 KST.
The earlier review rejection is historical and no longer blocks publication. Shell git push has no credentials;
GitHub connector publication succeeded and PR #102 is open.26 Kotlin cases are now in the candidate suite.
Review fixed a verified-host exception that could soften a user BLOCKED tier, checked long-click capability separately,
invalidated generic observations at dedicated-controller handoff, bounded unchanged-screen loops and matched typed-field
identity by resource AND bounds. Security CI also runs Android lint. The existing release workflow independently downloads
published public APK/checksum anonymously and checks manifest/version/code, certificate, native runtimes and source/tag,
with a verification artifact. Local release-assets downloads are network-blocked, so actual public download verification
must run on the release runner and its recorded output must be inspected.
Earlier local-blocked /19-tests notes are checkpoint history; do not report them as the current state after CI completes.

## Lint finding

CI39127351069 exposed one inherited error in McpHttpServer: InputStream.readNBytes requires Android API33,
but this app supports API31. Replaced it with DataInputStream.readFully, retaining incomplete-body400 behavior.
Two socket regressions cover fragmented and truncated requests. The candidate now adds28 tests total.
This fixes the reported API compatibility error without suppressing lint or changing the minimum SDK.

## Main delivery checkpoint —2026-10-19 KST

PR #102 merged as `2cbdb1a10fe81dd2f7f36dd3bef59eff21f15300` after final review.
PR Security CI39127838851 and signed native-runtime probe39127838744 succeeded.
Downloaded PR reports:2521 tests,0 failures,0 errors,0 skipped;28 new tests; lint0 errors/108 warnings.
Main Security CI39167500480 succeeded. Existing Signed Release39168050184 started automatically from that source.
The installed baseline remains v0.1.56/code57 until a higher-version update is independently confirmed.
The device currently reports remote-update consent and install-source permission true; no update request was sent before release verification.

- PR: https://github.com/al-hub/closepaw/pull/102
- PR CI: https://github.com/al-hub/closepaw/actions/runs/39127838851
- Signed probe: https://github.com/al-hub/closepaw/actions/runs/39127838744
- Main CI: https://github.com/al-hub/closepaw/actions/runs/39167500480
- Signed Release: https://github.com/al-hub/closepaw/actions/runs/39168050184


## v0.1.57/code58 delivered and remotely installed — generic device acceptance pending (2026-10-19 KST)

- [PR #102](https://github.com/al-hub/closepaw/pull/102) merged. Immutable release source/tag target: `2cbdb1a10fe81dd2f7f36dd3bef59eff21f15300`.
- Android **2,521 tests**, zero failures/errors/skips, including28 new cases. Lint **0 errors/108 warnings**; Python Termux55 + ChatGPT bridge8 passed.
  PR Security CI39127838851, signed native probe39127838744, main Security CI39167500480 and Signed Release39168050184 succeeded.
- [Published APK](https://github.com/al-hub/closepaw/releases/download/v0.1.57/closepaw-aa-bridge.apk):116,414,765 bytes;
  SHA-256 `7ef114d80b5aa7c46ff7adf85d7bfaf7e190bbee23a95545492e36364a1a30b6`.
  Anonymous public downloads on the release runner matched the built APK and checksum; manifest ai.closepaw/0.1.57/code58,
  original certificate, APK v2 integrity, both native runtimes and source/tag were verified. GitHub asset digest matches.
- **Remote update executed exactly once**, task `882ca03b-1c93-4018-a3c9-3e896454b700`.
  First status was installing on v0.1.56; later app response confirmed **installed/current v0.1.57/code58**.
  Remote-update consent and install-source permission remain true. The same existing MCP connection responds after replacement;
  no plugin re-registration, app removal, data reset or re-pairing was performed. This does not establish every retained setting or a warning's root cause.
- Installed discovery exposes `android.screen.observe`, `android.apps.list`, `android.ui.action`. All report `generic_control_disabled`,
  confirming the independent opt-in remains OFF. A read-only observe request was refused before screen capture, as designed.
- **Required user step:** ClosePaw Settings → ChatGPT Voice → **Allow ChatGPT Android screen control** ON, then leave an unlocked ordinary screen visible.
  Gemini model choices/requested selection and re-observation, one dedicated question/new reply, second-app navigation/scroll and Voice latency remain PENDING.
  Do not mark generic real-device acceptance or P2 DONE based on APK delivery/update. **P2 ACTIVE**.

## Delivery evidence detail

- Signed release run: https://github.com/al-hub/closepaw/actions/runs/39168050184
- Public verification artifact: `public-apk-verification-39168050184` (verification/signature/manifest text).
- Public APK verification stdout at2026-10-18 21:39:41 UTC reported package ai.closepaw, version0.1.57/code58,
  source_commit2cbdb1a10fe81dd2f7f36dd3bef59eff21f15300 and the published SHA/size above.
- apksigner independently verified the downloaded file's APK v2 signature and certificate SHA-256
  `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`.
  Verified native entries: libopenaitunnel.so28,970,086 bytes; libcloudflared.so29,003,496 bytes, both arm64-v8a.
- Update request: `generic-control-v0157-update-20261019-0640-01`, task882ca03b-1c93-4018-a3c9-3e896454b700,
  PackageInstaller session332382236. The task's succeeded/installing receipt was NOT counted as installation.
  The later self_update.status response reported installed/current0.1.57/code58 and preserved update permissions.
- The installed generic read refusal appeared as not_ready in the raw protocol and INVALID_ARGUMENT in connector-normalized metadata;
  both reported generic_control_disabled. This is the expected local permission gate, not a broken registration or connection.
- No real app contents, model list, question/reply, private app list or device screenshot were published.

Continue after the user enables the local switch: rediscover current schemas/readiness; observe the current screen;
list/launch Gemini by exact package; inspect actual model options; use the user's specified available model without substitution;
re-observe the selected model; hand control to the existing dedicated question operation once; confirm its new response.
Repeat safe navigation/scroll in a second ordinary app through the same generic operations and measure timings separately.
Do not bypass local consent or automatically replay unknown effects. No new APK is required merely to enable the switch.
This documentation-only checkpoint is newer than the immutable APK source; it does not create another release.
