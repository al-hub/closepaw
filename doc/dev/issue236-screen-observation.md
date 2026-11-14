# #236 explicit shared-screen observation

The user approved this separate mode after cancelling Samsung support contact.
It does not repair the original isolated Samsung IME buffer transform. Keep that
limitation explicit; do not relabel the existing strict refusal tests as a fix.

## Contract

`android.ui.observe(include_image=true, image_scope=screen_observe)` reads one
fresh MediaProjection frame only after a separate **local** opt-in using
Settings → Observe shared screen with ChatGPT → Android approval. This uses
Android 14's `MediaProjectionConfig.createConfigForDefaultDisplay()`. Ordinary
screen-sharing consent does not grant this mode. Stop an existing share before
starting the observation share; a live consumed round cannot be upgraded.

The result may include visible notifications or other app overlays in the
requested screen regions. `displayPlan` still limits the requested regions and
masks their complement, but this is not per-window pixel ownership verification.
Metadata states observation_only=true, visual_actions_allowed=false and
owned_pixels_verified_against_isolated_windows=false; certified regions stay
empty. No IME buffer origin, alignment or similarity is inferred. The existing
app/keyboard/display modes retain isolated verification and never fall back.

Both GenericControl and the Android native boundary reject all visual actions
from this scope. `VisualTargetProof.map` independently returns null. Semantic
accessibility actions still require their own normal native admission; an image
does not grant a node, gesture, input or submission capability.

The existing complete security scan, protected-app checks, secure-window probes,
IME read policy, sensitive-editor generation, session, rotation/window generation,
projection freshness and delivery/revocation checks remain in force. Raw frames
are invocation-local, masked before encoding, and recycled. No production image
is persisted or placed in the task ledger.

Official API references:
- https://developer.android.com/reference/android/media/projection/MediaProjectionConfig#createConfigForDefaultDisplay()
- https://developer.android.com/media/grow/media-projection

## Regression evidence

RED checkpoint `b1f0c435017370a38bd60e26692fcb61678df9e0`:
Security CI `43586825937` failed with raw JUnit3158/tests,3 failures,0 errors,0
skips. Only the three new positive/observation-safety cases failed: scope invalid
(two cases) and an observation image still mapped to a gesture coordinate (one).
The strict mismatch/no-fallback case passed. Artifact14109829919 SHA256
`e33ce01c5105ff2d1c428934b7b9c6f03de8c687575f3a716764d8b15b71cd28`, GitHub
digest and ZIP CRC independently verified. Runtime fix and GREEN evidence must
be recorded separately at their exact SHA; the RED port contract defaults to
null and does not implement the new mode.

JVM acceptance covers explicit request routing, truthful metadata, no visual
action dispatch/mapping, no strict fallback and cache revocation. Consent tests
cover ordinary versus observation rounds, token consumption, denied/cancelled/
reset/revoked states. API35 instrumentation calls the real WindowVisualCapture
with generated pixels to verify projection coordinates, complement masking,
absence of IME/display screenshot fallback, revoked delivery and incompatible
frame geometry; the original four geometry refusals remain.

## Physical candidate, before production release

An opt-in debug build property `-PclosepawCaptureCandidate=true` uses the
separate package `ai.closepaw.capturecandidate`, name ClosePaw Capture Candidate,
and version suffix -capturecandidate. It never replaces the production package,
uses no production signing credentials and is not a published Release APK.

The existing Premium UX UI workflow has a manually dispatched
`build_capture_candidate=true` job. Ordinary PR/main UI checks stay unchanged.
The candidate job builds arm64 app/test APKs and verifies the separate manifest
package/debuggable flag, source SHA and hashes. The default release build is
unaffected. Obtain the artifact for the exact candidate SHA only.

`PhysicalScreenObservationTest` is excluded from the automatic emulator class
allowlist. It runs only with physical_screen_observation=true and only against
the candidate package. It does not open apps, change settings or policy, approve
projection, type or dispatch gestures. It requires the locally enabled
accessibility service, generic control, permitted fixture apps and observation projection.
Standard instrumentation can restart the target, so the test waits up to180 seconds
for a fresh local observation approval in the test process and for the expected
foreground package. Start instrumentation BEFORE approving the observation share;
then reopen the candidate, approve sharing, and open the blank fixture. Never
assume a pre-instrumentation projection token survived. The phone help did not
advertise --no-restart; no unsupported flag or hidden API is used. Chrome and
Samsung Notes must be blank fixtures with the Samsung keyboard already visible.
It uses the real AndroidControlPort and local permissions. Candidate session
cleanup ends only the successfully started test session with the same session ID;
preexisting paused/waiting/active sessions are never replaced or terminated. Explicit export_image=true may save one private bounded
JPEG in the candidate cache for pixel/position inspection; retrieve then delete
that temporary evidence. Do not upload private screen pixels to GitHub.

Run separately for fixture_package=com.android.chrome and
fixture_package=com.samsung.android.app.notes. Inspect the actual images and
coordinates/masking, then verify applicable rotation, secure-window, sensitive
input and stop/revocation boundaries. Emulator tests do not replace Galaxy
acceptance. No release/merge is justified before required candidate verification,
final independent review and exact-head required CI. Existing signed main-only
release and public integrity gates remain unchanged.

Example development invocation (same-phone ADB, no PC/USB):

```sh
adb shell am instrument -w \
  -e class ai.closepaw.qa.PhysicalScreenObservationTest \
  -e physical_screen_observation true \
  -e fixture_package com.android.chrome \
  -e export_image true \
  ai.closepaw.capturecandidate.test/androidx.test.runner.AndroidJUnitRunner
```

Once the run starts, locally reopen the candidate and approve observation sharing
again. Generic-control/App Access choices persist, but projection consent does not.
Read the actual instrumentation success/failure result; a queued job or an old
cache file is never PASS. Keep physical images private and clear them after QA.
