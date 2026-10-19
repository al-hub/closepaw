# Explicit observed-node tap — v0.1.63 candidate

Status: development; no v0.1.63 APK, installation or model-selection acceptance claimed.

## Actual device evidence

On v0.1.62 the native model-title ACTION_CLICK was acknowledged without visible change.
The user's physical touch exposed an actual model picker and the user selected 3.8 Flash as
the requested target. The current selected model was different. A later fresh test first
dismissed the IME with Back, then native-clicked the re-observed clickable title parent.
Back changed the screen; the subsequent click was acknowledged with screen_changed=false,
feedback=acknowledged_no_visible_change (972ms), and a fresh complete104-node/depth33
observation still contained no picker options. A separate visual comparison also showed
no menu after a native click. The IME-only hypothesis is therefore insufficient.

## Contract

Add an explicit tap action with observation_id and node_id, never caller coordinates or
automatic fallback from native click. Original project handoff explicitly permits coordinates
linked to the latest observation while preferring semantic targets. Keep complete bounded
tree identity, consent, lock/secure/protected-app/input checks, shared mutex, unknown-send
guard, single-use/expiry and chain limits. Require the same current application window and
window geometry/occlusion context at the native boundary. Derive the touch from the current
visible enabled clickable node, require its rectangle inside the application window and its
center in that window's touchable region, and reject any covering window. Completion is only
gesture acknowledgement, never proof of model selection. Cancellation is unknown and is
never replayed. No model names in the executor.

First verify meaningful RED assertions against unchanged runtime, then implement and run
full CI/lint and signed probe. Publish and anonymously verify a signed APK, request one update,
confirm installed version/reconnection, and only then perform actual model-picker acceptance.
Any separate popup must pass foreground/security/identity checks; do not assume same-package
co-occurrence proves Gemini identity. Final acceptance requires observing the actual selected
model after choosing the user's requested option. No private screen/account/reply is published.

Implementation intentionally supports only the default display and rejects activated magnification
or touch exploration because Android transforms injected gestures in those modes. Window context
hash includes bounded window identities, types, layers, active/focus/PiP flags, bounds, touchable
region rectangles and display rotation. It is rechecked both before effect admission and immediately
at dispatch. Neither coordinates nor raw window context are returned to callers.

Android API contracts consulted:
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.GestureResultCallback
- https://developer.android.com/reference/android/accessibilityservice/GestureDescription.Builder
- https://developer.android.com/reference/android/accessibilityservice/MagnificationConfig#isActivated()
- https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo#getRegionInScreen(android.graphics.Region)

RED: Security CI39224348380/job117966897973 compiled and ran2,550 tests with exactly2 expected assertion failures: explicitNodeTapIsSingleUseAndDoesNotClaimModelSelection and cancelledTapIsUnknownAndMustNeverReplay. No compilation failure. Runtime was unchanged for this test-only PR head912d44f686acbe2c60529f9ff4149d9160a8c124.
