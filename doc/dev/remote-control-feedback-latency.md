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
- Screenshot probes start immediately when no preceding probe is known; known rapid probes retain400ms API spacing. Only an explicit Android screenshot-rate-limit refusal is retried once with a new verdict. Secure-window refusals are never retried. This removes the unconditional first400ms dwell without caching security results.
- Android event coalescing reduced100→25ms. No node or screenshot reads in the event callback.

## Validation status

User explicitly approved GitHub code upload, CI and signed APK release on2026-10-20 at07:10 KST after the initial automatic-review refusal.

[PR #111](https://github.com/al-hub/closepaw/pull/111) merged as16bdfb19885f5f548cfe511a25643f9eba4904b9. Final candidate source9999a7c98f7a5666a27d99c6c78416e624e29002 passed [PR Security CI39333648884](https://github.com/al-hub/closepaw/actions/runs/39333648884). Parsed artifact12226386374: **2,596 Android tests, zero failures/errors/skips**; lint zero errors/112 warnings/4 info. Python55+8, native and debug APK passed. Main CI39334583679 and Signed Release39335426773 both succeeded. Published v0.1.66/code67 APK is116,468,069 bytes, SHA-256 `d5cfebc81f0faf857ad26353ca50aece8a7abb7762c0cec682125382cc307d58`. Anonymous public download matched the build; manifest, original certificate `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`, both native runtimes and immutable tag/source16bdfb19885f5f548cfe511a25643f9eba4904b9 were verified by the release runner. Public verification artifact12225624162. [APK](https://github.com/al-hub/closepaw/releases/download/v0.1.66/closepaw-aa-bridge.apk).

The first CI39333143109 found an inaccessible View property at compilation. It was corrected to the public accessibility-node API; subsequent CI compiled and passed. No failed candidate APK was published. Local Gradle/pytest were unavailable; CI is the executed test evidence.

Pre-update matched calculator timing was not obtained: apps_query_timeout, followed by app_not_selected_or_blocked/action_attempted=false. No native calculator operation occurred. Record this as an observation, not an OS visibility diagnosis or a speedup measurement; no app-catalog fix is included in this scope.

## Acceptance after CI

Use the existing same-device calculator and Maps-to-calculator flows. Compare the total device timing; `validation_location=native_boundary` means `native_action` now includes the single native validation (phase values are not directly comparable to the old split). Measure device timing fields separately from MCP submission/result retrieval, ChatGPT planning and voice delivery; record medians/p95 over a small matched sample. Do not claim a measured speedup from fewer captures alone. Verify border appears during work, disappears on failure/cancellation/interrupt, passes through touches and does not invalidate guarded taps. Test foreign overlay coverage and secure/lock refusal. No new camera capture, message send, note edit or alarm change is required.

## Primary references

- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService (event callbacks, TYPE_ACCESSIBILITY_OVERLAY/window screenshots)
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#notificationTimeout (event coalescing)

## Device delivery

Exactly one self-update was submitted: task13ec4ee2-bf75-4341-b335-fee952185a9a. The first durable response was installing/target0.1.66/code67 while current remained0.1.65/code66. A later independent status confirmed installed/current0.1.66/code67 after reconnection; remote update/install-source permissions remain enabled. The first new-version screen query returned `blocked_or_cautious_app`, action_attempted=false/outcome_unknown=false. This is an unapproved-foreground refusal, not a device_locked observation. No calculator click, new photograph, note input or alarm change was attempted on0.1.66. Visual border, touch pass-through, tap regression and matched latency acceptance remain PENDING until an ordinary unlocked Home/ChatGPT/approved-app screen is foreground. No speedup percentage is claimed.
