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
