# Explicit observed-node tap — v0.1.63 candidate

Status: v0.1.63/code64 DELIVERED and INSTALLED; public download/signature and locked maintenance checks ACCEPTED. Actual node-tap/picker/model-selection acceptance and P2/Voice remain PENDING because fresh post-install observations return device_locked. Earlier development checkpoints below are historical.

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

GREEN: Security CI39225142460/job117969476264 succeeded on c854e99c5573a003e529bce2ee4898b3ad055f47.
Downloaded report artifact12174290316 was independently parsed:269 suites/2,559 tests,
0 failures/errors/skips; lint0 errors/108 warnings/4 informational. Python Termux55 and
ChatGPT bridge8 passed, as did bundled native runtime and debug APK. Separate signed probe
39225142311/job117969474990 is pending. These are code/build checks, not model-selection acceptance.

Signed Android probe39225142311/job117969474990 succeeded. PR #108 merged as d9bb229398f4121a14fe668d9ecf5f7d5666b900; main CI and immutable v0.1.63 release/public verification are next. No device-selection claim yet.

## Signed public delivery

Main Security CI39226838487 and Signed Release39227790943/job117978227314 succeeded.
Immutable source commit d9bb229398f4121a14fe668d9ecf5f7d5666b900; annotated tag v0.1.63
(4ab18a035ec0f9ccb9773fed970b3a75bcb83bca) dereferences to that exact commit.
Public APK: https://github.com/al-hub/closepaw/releases/download/v0.1.63/closepaw-aa-bridge.apk
Size116,431,913 bytes; SHA-256844c71c3157b09384ed0675860e9ea44cf83c7e826789f4df827f927c3d676dd.
The release runner independently downloaded the anonymous public APK and checksum, matched
built/public hashes, verified manifest ai.closepaw/0.1.63/code64, both native runtimes and tag/source.
Original signer SHA-256d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba retained.
GitHub public asset digest also matches the downloaded APK hash; Release is public/non-prerelease.

Before installation, fresh observation returned device_locked and latest update check returned
available0.1.63. Exactly one remote install request targeted0.1.63. It progressed to installing,
with a download/verify/install-request task completed successfully; that response alone was not
accepted as installed. The connection subsequently timed out during replacement. Reconnection
and installed-version checks follow; no install request was repeated. No UI action while locked.

## Installed-device checkpoint (2026-10-19 KST)

After the single install request and transient connection timeout, durable state confirmed
installed_version=current_version=0.1.63/current_code64, target0.1.63/code64, with the same
connection and both remote-update/install-source permissions retained. No repeated install,
re-pairing or plugin registration. New android.ui.action schema was rediscovered and advertises
tap; hash b0f449aabcc9227daad2d7e6c9d15b206c85f18cc4459db1bef0868204bd5373, ready=true.

Fresh post-install observation returned device_locked. A Termux id task succeeded (exit0,
no timeout/unknown outcome,78ms), followed by another device_locked observation. Latest release
check returned up_to_date. Thus installation/reconnection/maintenance were confirmed between
lock observations; continuous physical lock state during every instant was not instrumented.
No private shell output, screen image, account content or conversation is published.

No v0.1.63 tap, model selection, draft write or question send was attempted while locked.
Do not label the model change accepted from unit tests, a gesture callback or installation.
The requested target remains3.8 Flash, and selection is unverified. P2/Voice and matched
end-to-end/speech latency remain open;78ms shell duration is not a UI/Voice benchmark.

## Immediate unlocked acceptance

1. Fresh android.screen.observe must positively identify an unlocked ordinary screen. Rediscover
   current operation schema if necessary. Query android.apps.list(filter=Gemini) again (selection
   cache resets on update), select exact package and generic open_app with a fresh observation.
2. Observe actual model-title text and its clickable parent, require complete tree and
   node_tap_available=true. Native title ACTION_CLICK has already failed after IME dismissal;
   do not replay it. Explicitly tap the current title parent once using observation_id/node_id.
   Inspect confirmed task outcome and then a fresh observation. No stale coordinates/IDs.
3. Select only the actually observed option matching the user's3.8 Flash request; verify the
   selected title/checkmark in a fresh observation. A gesture/native acknowledgement is not
   application-result confirmation. If popup security/identity refuses observation, record that
   precise evidence and add a narrowly verified OS-linked window policy; do not relax Google
   host identity based on same-package co-occurrence or use a blind coordinate action.
4. Only after selection is confirmed, transfer the shared UI lock to the dedicated Gemini
   question path, preserve drafts and send one benign synthetic acceptance question. Observe
   the new reply; unknown send outcomes are never replayed. No new question was sent here.
