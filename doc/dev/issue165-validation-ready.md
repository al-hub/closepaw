# #165 implementation completion / validation entry

This record supersedes previous NOT_IMPLEMENTED statements for the source below.
It does not claim release, phone installation, real Termux acceptance or independent
review. Keep #165 open until its existing completion gates are actually verified.

## Implemented paths

| Requirement | Actual entry point and implementation |
|---|---|
| Three input methods | `android.ui.action(input)` and `mobile_action(type,input_method=auto/direct/clipboard/keyboard)` use the shared guarded engine. Legacy calls without input_method retain their established behavior. |
| Local invocation authority | Router-issued approved package/clipboard/terminal grants, immutable source selection and the exact running local session. A new per-invocation local control scope never starts/resumes/modifies the remote session. Remote and local input share a UI mutex. |
| Noneditable terminal | `android.ui.action(terminal_input)` uses Android's official accessibility InputMethod/AccessibilityInputConnection API, not keyboard replacement, shell bridge or guessed cursor taps. Verify native binding generation/package/field resource, focused Termux TerminalView, supported NULL-class editor, empty composition buffer and visible empty prompt. Commit whole single-line text once and compare exact visible echo. Local unified input routes to the same path only with a router-confirmed terminal approval. |
| Separate terminal submission | `terminal_submit` additionally requires submit_authorized and exact pending command. Commit one newline; never combine submission with filling. `expected_output` requires newly appended exact output and returned prompt. No output/timeout/partial result remains UNKNOWN, with no replay and no inferred process exit code. |
| Dynamic IME | Reuse OS-selected/authorized IME observation and actual key bounds, key-by-key text/Hangul verification and observed Shift/language/symbol transitions. Fix native key geometry validation to compare the app's original geometry frame before targeting the separate IME window. |
| Visual targets | `android.screen.observe(include_image=true,session_id=...)` captures only the proven window. Actual image is returned as an MCP image block when get_task reads the successful result. Metadata-only task ledger; at most two images in memory with live consent/session+expiry checks. |
| Coordinate transform | Native window origin/size and exact raw buffer dimensions, scaled image width/height, window geometry/rotation/context and pixel hash are bound to the observation. Ambiguous crop/insets or stale pixels refuse. Only image rectangles in that exact snapshot are mapped; no external display coordinates. |
| Visual short/long touch, drag/scroll | `visual_tap`, `visual_long_press`, `visual_drag`, `visual_scroll` require fresh observation/visual ID, confirmed visual target and image_rect; drag/scroll also image_end_rect. Re-probe full security/tree/geometry, compare a new screenshot's exact pixels, verify occlusion/window region/control UI along the full path, then dispatch once. |
| Clipboard | Existing explicit consent+replacement policy, readable provenance, separate stage/Paste guards, focused Paste-only target support and exact effect checks remain. No false-empty assumption or blind restoration. |
| Stop/privacy/regression | Session epoch/owner, generic consent, app policy, lock, protected content, revoked grants and X are checked at native boundaries. No unknown-effect fallback. Screenshot cache is cleared on stop/service destroy; retrieval also requires live consent and session. New diagnostics do not log input/clipboard/screen strings. |

## Native requirements and supported refusal states

- Generic native security probing still requires Android14+. Accessibility InputMethod
  itself starts at Android13, enabled via the documented service flag/callback.
- No change to the user's default keyboard, permissions, app overrides or phone
  configuration is performed by development. Device validation may discover that
  Android must reconnect the service after an APK update; do not assert that occurred.
- Terminal adapter is intentionally narrow: `com.termux`, actual terminal resource
  and its custom/base-View accessibility class, a native OS InputConnection bound to the same field, empty composition,
  a visible empty `$` prompt, non-sensitive single-line text <=256, no trailing
  whitespace. Visible echo is complete and bounded to8192 chars. No blind canvas input.
- Termux's documented Samsung char-based input type `0x00080090` has NULL class;
  accept only this exact known value or0. Standard text/visible-password/number-password
  editors remain refused, as do node password flags and protected screens.
- Truncated/wrapped-away/moving terminal echo, unsupported editor metadata, unknown
  prompt, absent accessibility connection or changed generation refuse/UNKNOWN.
  These are designed runtime outcomes, not permission to inject via a weaker path.
- Visual images expire after60 seconds and are invalidated/consumed with observations.
  Exact pixel comparison can refuse animated/blinking content. There is no display
  screenshot fallback after secure/invalid-window denial and no image persisted in
  a public artifact or operation task ledger.
- Visual gestures currently operate on the application window. Semantic IME key
  operations use their independently validated IME window. Do not target an IME by
  pretending its coordinates belong to an app image.

## Automated contract tests

JVM: actual local tool routing/provenance, per-operation approval floor, local owner
revocation without remote resume, terminal binding/prompt/secret/newline refusal,
exact echo/no duplicate dispatch, separate command submission/output, image scale+
window origin, rotation/fingerprint refusal, image revocation/invalidation and actual
visual action single consumption. Existing full JVM/security/UI suites remain required.

Optional `-PclosepawInputFixtures=true` includes a tiny Java-only `terminal-fixture`
APK in CI. It is never a dependency of the production app or release build. The
throwaway emulator installs this distinct `com.termux` test app, then real Android
accessibility InputMethod binding/commit/exact echo, separate submission, protected
editor refusal and screenshot-mapped native touch are exercised. This fixture
runs no shell and is not evidence that the actual Termux app or phone passed.

## Remaining phase: verification and delivery

1. Latest-source Security CI/JVM/lint/debug and native UI/fixture results; investigate
   and fix real failures without deleting assertions or disabling safety checks.
2. Independent code/security review of the new authority, InputConnection lifecycle,
   clipboard race and screenshot/gesture paths; address findings.
3. Existing-key signed build/public hash+signature/source verification and installation
   if proceeding to the previously authorized delivery phase.
4. Same installed APK on actual2 apps + actual Termux: A/B/C methods, Korean/English/
   digits/symbols/space/delete/Enter, default/alternate IME, rotation/scroll/drag,
   physical X/no replay and matched performance. Mark only actual results PASS.

No planned functional development placeholder is hidden behind a permissive
callback: each new public path is connected to its guarded native implementation.
Any defects found by the pending independent/device verification must still be fixed.

## Primary contracts consulted

- https://developer.android.com/reference/android/accessibilityservice/InputMethod
- https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection
- https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java
  (inspected blob f4f82ad7c51efd95db914f26d39dc6a535a2f7e4)
- https://github.com/termux/termux-app/blob/master/terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java
  (getSelectedText trims trailing spaces and exposes rendered echo, not a shell exit code)
