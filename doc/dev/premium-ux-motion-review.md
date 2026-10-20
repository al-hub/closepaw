# Premium interaction motion review

Date: 2026-10-20. Reviewer: AI Senior Visual/Motion Designer role; no human expert participated.

## First review — baseline and design

The original remote indicator used a saturated opaque label, a single cyan stroke and a 650ms ring at the target rectangle's center. That center was intent feedback and could not be described as an injected touch. Existing user-priority session gates and pass-through decorative window are retained.

Calm Intelligence uses layered low-opacity cyan/blue strokes, a slower 6.6-second ambient breathing cycle, rounded low-opacity target surfaces, and a compact readable light/dark status capsule. Amber means a user response is required; muted blue-gray means paused. Text and panel labels carry the same meaning independently of color. Planned targets use dashed contours, while submitted actions use solid contours. No fake completion percentage is rendered.

## Implementation

`PremiumControlDecoration` owns Canvas rendering and ephemeral coordinates only. `PremiumMotionPolicy` contains bounded lifetime and animation gate logic. The renderer has no authority to execute or confirm an action. It receives:

- `target(rect)` for a planned safe target, marked '대상 확인'.
- `action(rect, kind, completed)` for native action acknowledgement. False acknowledgements get a restrained warning color; a true acknowledgement is never a verified app outcome.
- `tap(rect,x,y)` for the exact injected screen point, with a soft radial light and outward ripple.

Semantic accessibility scroll has no physical swipe trajectory. Its area and direction arrow are shown; no fabricated finger trail is drawn. Double tap, drag and physical swipe are not introduced by this change and must not be claimed as supported. Text contents, authentication information, screenshots and user input are never copied into the renderer.

Motion respects Android animator settings and TalkBack touch exploration. Paused state is static. Non-active user-controlled states clear target/action effects. Screen-off draws no effects and queues no frames. Detached or resized views clear stale geometry; screen coordinates are converted to overlay-local coordinates. Reduced motion uses a single expiration redraw rather than a static 30fps loop. Full-screen blur and per-frame large bitmap allocations are avoided.

## Second review — source assessment

| Criterion | Result | Evidence |
| --- | --- | --- |
| Calm, layered light with bounded transient effects | Pass (source) | Low-opacity strokes; 720ms actions / 900ms planned targets |
| Actual point distinguished from planned rectangle | Pass (API/source) | Separate target/action/tap APIs; exact tap coordinates required |
| No invented scroll/swipe path | Pass (source) | Semantic scroll draws direction and target area only |
| Paused/manual/offscreen effect shutdown | Pass (source) | State, interactive and detach gates |
| Reduced motion/TalkBack | Pass (policy source) | Animation gate and single expiry invalidation |
| Light/dark text capsule | Pass (source) | Configuration palette, ellipsized scaled text |
| Device visual smoothness, coordinate alignment, fold/rotation, contrast measurement | Not Tested | Physical device/emulator not available in this worker |
| JVM test execution | Not Tested locally | Gradle distribution unavailable; network unreachable before test runner |

Added `PremiumMotionPolicyTest` checks all animation suppression gates and effect progress/fade boundaries. Parent CI must execute it. Visual acceptance remains required: soft motion and readability, actual tap point, keyboard/rotation/fold geometry and battery/frame stability. Source inspection is not a substitute for measured performance or human aesthetic acceptance.

## Integrated second source review — actual phase wiring

Reviewed the integrated `ControlSession.VisualPhase`, `GenericControl.presentation(expectedEpoch)`, Android native event wiring, `RemoteControlPanel`, `Color`, `Theme`, and `CalmAmbientSurface` on 2026-10-20. This is an AI role source review, not human expert approval or a physical-device visual review.

- **Pass (source):** observed/executing/waiting/completed/failed phases originate in capture and action/postcondition results. Completed is emitted only after the existing postcondition verification; a submission ripple is separate from an app result. Failure and completion use restrained error/teal accents rather than celebratory success effects on submission.
- **Pass (source):** `ControlSession.present` accepts active-state events only, with the expected epoch matching the current session. The added `lateVisualEventsCannotOverrideUserControlOrNewSession` regression test covers paused and restarted-session visual-phase rejection. CI execution is still pending at this review.
- **Pass (source):** injected tap ripple receives the exact `NodeTapPolicy` point only when `dispatchGesture` is submitted and the session epoch still matches. Semantic node scroll draws its actual target area and direction without inventing a finger trajectory.
- **Pass (source):** app surfaces and overlay share cool porcelain/deep navy, blue/cyan accents, rounded shapes and restrained depth. `CalmAmbientSurface` caches 8% blue and 5% cyan radial gradients without running an idle animation. Native panel controls use 56dp minimum height and quiet native ripple/haptic feedback.
- **Pass after correction (measured palette/source):** The initial tertiary contrast finding was corrected to light `Paper` on `Amber` (4.68:1) and dark `PaperDark` on `AmberDark` (11.57:1). Primary pairs measured 5.70:1 light and 9.47:1 dark. `CalmThemeContrastTest` now checks actual Material primary/secondary/tertiary/error/surface/container foreground/background role pairs against 4.5:1 in both themes. Test execution awaits CI. These are sRGB token calculations, not rendered-device contrast measurements.
- **Pass after correction (source):** The stale completion-label finding was corrected with `ControlSession.feedback(text, expectedEpoch)`, `ControlPort.feedbackAtEpoch`, and the Android override. Both final observe/action labels carry their original visual epoch. The restarted-session regression now asserts that an old result label cannot replace the new session label. This review establishes the generic operation final-feedback path; it does not claim every possible producer in the whole app was exhaustively race-tested. CI execution remains pending.

Physical screenshot review, full font-scale/clipping assessment, animation frame timing, exact-coordinate alignment on device, battery draw, and human evaluation of motion remain **Not Tested**. A pass in source review must not be promoted to those acceptance results.

### Corrective re-review

The two findings above were re-read against the final integrated source. Both are resolved at source/token-calculation level. No implementation files were changed by this re-review. Overall motion/design review result: **Source Pass; automated runtime validation pending CI; physical/human visual acceptance Not Tested.**

## Final executed delivery evidence (2026-10-20)

PR #123 merged as96cc7013a4f00bb7e2c3c5c648360740785e33ae. PR Security39393879629/UI39393879612, main Security39394905098/UI39394905106 and Signed Release39395656700 all succeeded. Main reports:2632 JVM tests/0 failures/errors/skips; lint0 errors/119 warnings/4 info; Python55+8;6 native View UI tests and4 corrected render files. Actual normal/200% Light/Dark panel renders were reviewed; the large-font Pause truncation was fixed and guarded by no-ellipsis assertions. Original signer, manifest0.1.70/code71, source/tag, native runtimes, checksum and anonymous public APK download passed; proof artifact12252261387. [Full final evidence and acceptance matrix](premium-ai-human-ux-v0170.md). This final result supersedes initial pending-CI entries; local Gradle/pytest execution and an automated local RED were not available. Physical/human acceptance and measured device performance remain Not Tested.
