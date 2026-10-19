# Remote control visibility and latency — 2026-10-20

## Scope and HOLD

User approved proceeding with two improvements: distinguish ClosePaw remote UI control from user activity, and react to observed screen changes instead of fixed settling sleeps.

The following v0.1.65 device findings are recorded **HOLD**, not being fixed or retested in this change:
- Samsung Notes: new note opened; native SET_TEXT did not acknowledge input and fresh read stayed empty. No replay.
- Clock: seven visible alarm switches were off; outer scroll collapsed the header but did not advance the remaining list. Full enabled-alarm enumeration remains partial.
- General post-transition races are relevant to this latency work, but app-specific input/scroll fallbacks remain HOLD.

Verified baseline on the same installed APK: camera single capture and new saved-photo metadata; calculator 39,000×0.8=31,200; Kakao first-screen read; Maps Suwon station search/selection; return to calculator with result retained. Do not publish captured contacts, notes, addresses, photos or account details.

## Candidate implementation

- A service-owned cyan border and `ClosePaw 자동 제어` badge during generic remote observation/action and the shared UI lease used by registered app reads and dedicated Gemini operations. Nonfocusable, nontouchable; removed in finally (including timeout/cancellation), interrupt and service destruction. No claims about physical-touch attribution; the badge indicates ClosePaw operation ownership. Human touches are not blocked.
- Only the exact attached indicator window ID, and only TYPE_ACCESSIBILITY_OVERLAY, is excluded from generic window identity/tap-occlusion accounting. Other overlays retain all guards. Removal/creation does not invalidate targets merely because this known noninteractive surface exists.
- Native Android perform retains a NEW secure-window probe, complete security tree traversal and action-scoped freshness checks. Skip the duplicate engine-level capture only when the adapter explicitly owns that native validation. Other/fake adapters retain engine validation.
- Service-owned text-free event generation for window/content/text/scroll changes; events from ClosePaw are ignored. StateFlow prevents missing an event between checking and suspending.
- Post-action capture runs immediately. On an incomplete/transient/unchanged screen or pending exact-package launch, wait for a new event, then recapture. Maximum four reads and 1,600ms observation scheduling budget; unchanged nonlaunch acknowledgement waits at most250ms for a missing event. The overall existing10s operation timeout still bounds slow Binder/probe work. No event proves success, no action is replayed. No security verdict is cached.
- Existing screenshot API throttle is retained. It is a platform-probe spacing guard, not a task-completion timer.
- Android event coalescing reduced100→25ms. No node or screenshot reads in the event callback.

## Validation status

Local Python bridge tests also cannot run because pytest is not installed. `git diff --check` and XML parsing pass; these are static checks only.

Local Gradle execution is blocked before compilation: the Gradle distribution is not installed and services.gradle.org is unreachable. Added deterministic JVM tests for immediate event wake, check/subscribe race, timeout/cancellation, exact-package launch, incomplete trees, security refusal, event storms and native-validation capture counts. They are **not run** yet. Full Android compile/lint/JVM CI and device checks remain required before any merge, version bump or release.

User explicitly approved GitHub code upload, CI and signed APK release on2026-10-20 at07:10 KST after the initial automatic-review refusal. Upload and CI can now proceed. Candidate version0.1.66/code67 is not delivered until CI/release evidence is recorded.

## Acceptance after CI

Use the existing same-device calculator and Maps-to-calculator flows. Compare the total device timing; `validation_location=native_boundary` means `native_action` now includes the single native validation (phase values are not directly comparable to the old split). Measure device timing fields separately from MCP submission/result retrieval, ChatGPT planning and voice delivery; record medians/p95 over a small matched sample. Do not claim a measured speedup from fewer captures alone. Verify border appears during work, disappears on failure/cancellation/interrupt, passes through touches and does not invalidate guarded taps. Test foreign overlay coverage and secure/lock refusal. No new camera capture, message send, note edit or alarm change is required.

## Primary references

- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService (event callbacks, TYPE_ACCESSIBILITY_OVERLAY/window screenshots)
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#notificationTimeout (event coalescing)
