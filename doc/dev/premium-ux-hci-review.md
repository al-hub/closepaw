# Premium control — AI HCI role review

2026-10-20. This is an AI expert-role review, not a review by a human UX professional.

## First review: baseline and design

The original floating control changes Pause to a response-complete checkmark during WAITING. That removes the user's immediate pause affordance at the precise moment the user is interacting. The original controls are icon-only, the control collision policy only checks a target's center, and there is no explicit manual takeover or task-stop result.

Keep the existing ControlSession state machine, persistent restart refusal, epoch invalidation, cancellation callback and physical-only guarded reobservation. Add a bounded, native View panel instead of a new fullscreen touch receiver. Decoration remains FLAG_NOT_TOUCHABLE; only panel and independent emergency X accept input.

| Physical action | State / consequence | Visual feedback |
| --- | --- | --- |
| Pause, including while waiting | PAUSED, epoch increments, cancellable work interrupted | Resume replaces Pause at same location |
| Manual takeover | PAUSED, epoch increments, cancellable work interrupted | Explicit direct-control label |
| Resume | REOBSERVING then ACTIVE only after safe fresh check | Main panel removed during observation; emergency X remains |
| Response complete | WAITING → PAUSED → physical resume check | Separate button, never replaces Pause |
| Stop | ENDED; old/queued work refused, no resume | Bounded stopped-result panel; no progress animation |
| Upper-right X | ENDED; old/queued work refused, no resume | All related control windows removed |

Stop and Close share the same strong session gate. Neither undoes an external app's already-completed effect. A new authorized session is required after either action.

## Implementation

RemoteControlPanel supplies 56dp minimum-height controls with text and icon, 8dp gaps, polite live-region status, native ripple and system-respecting haptic feedback, dark/light styling, fold/expand and a drag handle. Collapse retains Pause and Stop. RemoteControlIndicator bounds the panel width, raises it above observed IME windows, recalculates placement, keeps the independent X, and rejects target rectangles intersecting any touchable control window. Layout updates run only on changed geometry. Existing screen-off guard and service cleanup remain in place.

## Second review and evidence boundary

Source review: Pass for priority and retained gates; no AI operation exposes manual resume. Direct-control feedback cannot be overwritten by AI feedback. WAITING pause no longer acknowledges a response. Stop and X cancel through the existing interruption hook. Physical emergency X remains while guarded resume observes the external app.

Added JVM regression cases: manual takeover invalidates epoch, cancels once and blocks stale feedback; WAITING can pause without response acknowledgement. Added native instrumented View cases: separate waiting pause/response, takeover/stop refusal, collapsed primary controls.

Local Gradle test invocation could not download Gradle 8.11.1 because network access to services.gradle.org is unavailable. This is a test-environment block, not a passing suite or an expected failing assertion. CI execution results must be appended by the integration owner.

Not Tested on a physical device: TalkBack announcement/focus, upper-right X actual hit routing, IME overlap at large fonts, drag placement during rotation/fold, actual cancellation latency, safety of already-injected gestures, GPU/battery, translucent window composition. Especially small landscape + IME may have insufficient vertical room; emergency X remains separate, but this layout needs physical acceptance and should not be described as universally verified.

No screenshots, input contents, app inventory or credentials are recorded in this report.

### Cross-review correction

Product UX identified two source-level P1 issues before CI: optional controls could exceed a small IME viewport, and recreating secondary buttons on every status update could lose TalkBack focus. Both were corrected: secondary controls are stable View instances, status is limited to two visual lines while its accessible description retains the full state, and compact/minimal modes remove optional content before reducing the emergency row. Placement clamps to screen bounds. In an extremely short landscape area consumed by the IME, the bounded emergency row may overlap the keyboard to remain reachable; the separate X remains available. This is an explicit layout tradeoff requiring physical acceptance, not a universal no-overlap claim.

Added instrumented cases measure the emergency-only row at 200% font size in a 100dp viewport and retain button identity across feedback. These tests are authored, not locally executed; connected-device results remain Not Tested unless supplied by CI/device evidence.
