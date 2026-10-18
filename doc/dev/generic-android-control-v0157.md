# Generic Android control v0.1.57 — design and acceptance

Status: implementation submitted in PR #102; Android validation in progress, release not delivered. Baseline main 6662e95f, installed v0.1.56/code57.
No open PR existed at start. Existing updater reports installed57, consent and install-source permission true.

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

RED regression tests cover stale/wrong/expired/consumed observation IDs, consent revocation, truncation,
missing node, nonempty draft, protected screen and unknown click outcomes.
Local Android Gradle is unavailable because services.gradle.org is unreachable; use PR/main CI.

Pending: GREEN tests, full suite/lint/build, review, PR/main CI, signed release, independent public APK verification,
remote updater replacement/reconnect, local opt-in, Gemini menu actual requested model selection/re-observation,
one dedicated question/new answer, a second ordinary app and Voice latency. P2 remains ACTIVE.

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
