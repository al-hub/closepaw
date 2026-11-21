# #276 — keyboard typing preflight first, before another Galaxy release

## Problem and status

The v0.1.137/code138 acceptance attempt for #147 ended **before any character or
Space was dispatched**. The Chrome `com.android.chrome:id/url_bar` was visibly
empty and focused, but its raw accessibility selection remained `-1/-1`. The
`bound_empty_editor` keyboard association is **not** proof of absolute caret
`0/0`. A native `focus` request was refused without effect. A later guarded
`tap` on the already focused field created Chrome floating editing toolbars,
and the keyboard input-target binding became unverified. This means the attempt
did not exercise the visual key sequence or pixel stage at all.

Evidence:
- [Latest device record](https://github.com/al-hub/closepaw/issues/147#issuecomment-7054284144)
- [Sanitized metadata fixture](evidence/issue276/galaxy-v0137-preflight.json)
- [Original #147 six-key acceptance runbook](issue147-guarded-key-map-acceptance.md)

## Low-cost off-device gate (current PR)

Run the production-policy JVM regressions **without a phone, release or signing**:

```bash
./gradlew :app:testDebugUnitTest --tests "ai.closepaw.chatgpt.control.KeyboardPreflightRegressionTest"
./gradlew :app:testDebugUnitTest --tests "ai.closepaw.chatgpt.control.KeyboardCursorProofTest"
./gradlew :app:testDebugUnitTest --tests "ai.closepaw.chatgpt.control.KeyboardSequencePixelProofTest"
```

The first suite reuses **actual existing production policy**:
- raw `-1/-1` is not valid caret `0/0`; native surrounding-text proof needs
  empty text, collapsed selection `0/0`, and offset 0, together with stable
  editor/IME identity checked by `KeyboardCursorBinding`;
- a newly selected Chrome floating popup/window or changed focused editor is
  a stop, never authority to continue keyboard input;
- the JPEG `443x960` rectangles and `1440x3120` screen node rectangles
  are **different coordinate spaces**;
- owning the interior JPEG crop does not imply that all physical key-node
  pixels are independently owned.

Synthetic and redacted geometry may verify the policy, but **cannot
reconstruct the original missing image_rect or owned pixels**, prove why
`KeyboardCursorBinding.prove()` returned no native proof, or establish a real
Galaxy acceptance PASS. The two already-existing proof test suites retain their
own additional cases; do not duplicate or relax them.

## Required no-touch gate for next acceptance (future work, not implemented here)

1. **No-touch editor preflight**: one isolated non-sensitive Chrome test editor,
   chosen explicitly. Read foreground, selected owned application window,
   popup inventory, focused editable field identity, exact empty string,
   numeric caret evidence and bound IME. Report distinct outcomes for
   `empty_caret_unproven`, `popup_present`, `input_target_unverified`,
   `focus_changed`, `keyboard_visual_unavailable`. Do not tap an already
   focused empty editor to manufacture caret proof. Do not translate `-1`
   to `0`.
2. **No-touch visual preflight**: request one fresh keyboard JPEG and the four
   independently unique physical candidates `ㄱ/ㅏ/ㄴ/Space`. Read actual image
   before selecting each interior **image-space** rectangle; check disclosure,
   screen/IME/node containment, full owned pixels, rotation/window generation
   and the source editor. Missing image_rect/ownership is UNKNOWN/BLOCKED, not
   permission to estimate rectangles.
3. **Full status matrix before action**: all conditions PASS in the **same
   session and display generation**; popups absent; consent active; no
   truncation. This preflight is strictly read-only. Native dispatch must still
   repeat all security/pixel/window/selection checks at the touch boundary.
   If any status is UNKNOWN/BLOCKED: stop without touching or retrying.
4. **Only then** a new expressly authorized Galaxy one-shot six-key acceptance.
   Mixed benchmark 3 trials only after exact `가 나 ` / caret `4/4`.
   Chrome address-bar compatibility and a local HTML editor are different
   acceptance targets; a local HTML test alone cannot resolve url_bar behavior.

## Shipping gate and cost controls

- No production code changed in this first PR. Do not bump APK, merge to main,
  release, install or start device control just to run this fixture.
- Green JVM tests + PR Security/UI gates mean **offline policy regression PASS**,
  not that native cursor binding or Galaxy typing has been fixed.
- Before adding a new production `typing.preflight` API, inspect the existing
  `android.screen.observe` / `KeyboardCursorBinding` path. Share its
  authoritative checks rather than implementing a second competing validator.
  If additional production diagnostics are genuinely necessary, introduce
  read-only structured reason codes first, preserve privacy and safety, and
  make one batched candidate release **only after** an independent fix and
  full off-device tests.
- #147 remains OPEN until physical 6-key and fixed mixed 3/3 acceptance are
  separately obtained; no numeric improvement rate without a matching 3/3
  baseline.
