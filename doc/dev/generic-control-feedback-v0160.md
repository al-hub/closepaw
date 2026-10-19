# Generic control reliability and feedback — v0.1.60/code61

## Scope and acceptance boundaries

2026-10-19 KST the user authorized fixing app discovery, Gemini model-menu handling,
foreground identification and action-to-feedback latency, through a signed release,
remote update and locked-device checks. Do not wake/unlock the phone or perform UI
acceptance while it is locked. An installed APK is not model-selection acceptance.

## Confirmed v0.1.59 baseline (same-day device checks)

- Signed v0.1.59/code60 remotely installed and reconnected.
- Before/after observations returned `device_locked`; update status/check and a
  Termux `id` command succeeded in that interval (exit0). This is not evidence of
  installation while locked, long-term Doze survival, restart recovery or all shell commands.
- Unlocked: current ChatGPT screen read, Home transition to the launcher, Gemini
  launch using the existing registered read route, generic sidebar close.
- Short synthetic arithmetic question sent once and correct new reply collected in
  approximately8s; a35-item synthetic reply including its terminal marker was
  collected in approximately13s with complete coverage. No private screen text is published.
- Chrome launch/read, generic menu open, Back closed that menu.
- `android.apps.list` with a label filter failed with a generic read-operation error.
  Exact cause was not exposed. Generic search-to-selected-app launch was NOT accepted.
- Model-title click was acknowledged but did not produce a visible picker. Model
  change was NOT accepted. Do not automatically repeat that click or invent model options.
- Gemini registered screen read returned `target_not_foreground`; subsequent generic
  observation positively identified a native Gemini sidebar.

## Implementation plan and changes

1. App catalog: avoid constructing the entire accessibility platform just to enumerate
   packages. Query launchable activities once on IO, apply existing app policy before
   expensive label loads, deduplicate before label resolution and resolve Home once.
   Clear old package selections before a query. Preserve explicit lock/permission state,
   distinguish lookup failure/timeout and do not turn unavailable queries into empty success.
   The exact previous on-device exception remains unconfirmed until acceptance.
2. Read identity: identify a composerless Google-hosted Gemini shell by multiple exact
   native resource identifiers and an active/focused application window. A lagging package
   event is not authoritative over that verified root. Generic Google Search and background
   roots remain rejected. This read identity does NOT grant composer/write/send authority.
3. Menu controls: expose class/selection and advertised native expand/collapse actions in
   generic observations. Only execute the action actually advertised by the selected node;
   retain fresh complete-tree validation and all security gates. No model-name branches,
   coordinate gestures or repeated-click fallback. Actual Gemini picker acceptance is pending.
4. Feedback: replace unconditional screenshot waits with remaining API-interval spacing;
   always perform a fresh secure-window probe. Remove the redundant native post-action sleep.
   Add validation/native-action/post-observation/total timings and explicit
   `acknowledged_no_visible_change` vs `screen_change_observed` feedback. Both require the
   caller to inspect the returned screen; neither asserts the intended model was selected.
   Bounded task reads now return completed generic UI work without another MCP round trip.
   Network/assistant/speech latency is outside device timing and must be measured separately.

## Validation and delivery

PR#105 first adds regression tests against unchanged implementation. Local JVM execution
is unavailable because the Gradle distribution download is network-blocked and this host
has no Android SDK. Use actual GitHub CI logs/reports; do not call this a passing local build.

PR#105 merged as `841c183d08589054072f2e31f1605f1b8b57fff9`.

- RED run39191805021 compiled and ran2,535 tests; exactly the four new regressions failed
  (bounded generic task waiting, active-root identity, safe lookup failure, explicit feedback).
- Intermediate run39192219570 ran2,544 tests with3 failures caused by Android JVM stubs
  returning null from fluent Intent.addCategory. Preserve the constructed Intent using apply;
  fix commit `df18e65039f7a7db2c5c2550d0f289db176b7f6a`.
- GREEN PR run39192676926 succeeded. Downloaded report artifact12160856119 independently
  parsed:2,544 tests,0 failures/errors/skips; lint0 errors,108 warnings,4 information notices.
  Python Termux55 and ChatGPT bridge8 checks, native cloudflared build and debug APK passed.
- Main CI39193365124 and Signed Release39193963265 succeeded. Anonymous public APK download,
  SHA-256 equality with the built APK/checksum, ai.closepaw/0.1.60/code61 manifest, original
  signer and both native runtimes were verified by the release job.
- Published APK:116,424,773 bytes, SHA-256
  `6f30df8b758e7e5c63279b2a01a0586a0fa1f2f982df7e497eca27045c9fc31c`.
  Original certificate SHA-256 remains
  `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`.
  Public verification artifact12160864624 records manifest/signature/download evidence.
- Exactly one remote installation request was submitted, task
  `70201e6e-dd32-43a0-8899-d09910ff8226`. Initial durable state was installing/code60;
  a status query across replacement did not yield usable confirmation. A subsequent
  successful query confirmed installed/current0.1.60/code61, with remote-update consent
  and install-source permission still true. No repeat installation, re-pairing or plugin
  re-registration. Installed discovery returned all14 operations ready, including the
  new generic action schema hash5a3328997e102ebc277c20d60256478cfd49aa1194edc2d732e231e351b95b40.
- Background/non-UI device checks passed: previously failing Gemini label lookup now
  returns exactly its installed launchable package; latest-update check is up_to_date;
  registered browser/Gemini targets are ready. Termux id task
  `7377b2c0-2c7b-4816-b518-4e62248910c1` succeeded, exit0, no timeout/unknown outcome.
  Task start/completion timestamps are present; shell executor duration103ms is not a UI
  or speech-feedback latency measurement.
- Tailscale diagnosis returned overlay_link_observed, VPN/overlay address present,
  no connection change requested. VPN owner/login and private target reachability were
  NOT established (no target supplied/probed); do not claim SSH/PC acceptance.
- IMPORTANT: before and after installation, screen observe returned
  blocked_or_cautious_app, action_attempted=false, rather than device_locked.
  No app launch, model change or UI mutation was performed in this delivery check.
  These results do NOT prove installation or maintenance while locked on v0.1.60.
  Known-locked v0.1.60 acceptance remains PENDING; the historical v0.1.59 locked check
  above is not silently carried forward to this version.

The device action result timings measure validation/native action/post-observation. Task
start/completion timestamps expose the device executor interval; they do not establish
network, assistant reasoning, speech-generation or the user's perceived feedback latency.
The first secure-window probe after adapter handoff retains a conservative400ms gap; later
probes wait only for the unelapsed API interval and never reuse a security verdict.

Unlocked acceptance remaining: label search + exact selected launch, Gemini picker and
requested-model selection, foreground read across sidebar/composer, and before/after
end-to-end latency. Do not close P2/Voice acceptance from unit tests or locked checks.
