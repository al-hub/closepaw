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

Pending: RED result, GREEN suite/lint, signed probe, main CI, immutable signed release,
public-download signer/hash/manifest validation, remote installation, reconnect and locked
maintenance checks. Record each actual result below as it becomes available.

Unlocked acceptance remaining: label search + exact selected launch, Gemini picker and
requested-model selection, foreground read across sidebar/composer, and before/after
end-to-end latency. Do not close P2/Voice acceptance from unit tests or locked checks.
