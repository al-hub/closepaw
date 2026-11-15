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

## Physical candidate: local first, ADB comparison later

The user approved BOTH routes because Wi-Fi is unavailable for an extended period.
`CaptureQaAcceptance` lives in `src/debug`, is exact-package guarded, and is shared
by the non-exported `LocalCaptureQaActivity` and manual `PhysicalScreenObservationTest`.
Release source sets contain neither QA class nor its provider/manifest activity.
The Settings row and wizard-only onboarding bypass require DEBUG plus exact
`ai.closepaw.capturecandidate`; actual OS permissions, app policy, generic-control
consent and projection approval are never granted by the harness.

Candidate builds use `-PclosepawCaptureCandidate=true`, a separate appId and label,
and the public debug `ndk.abiFilters` to package arm64. The manual candidate job uses
no IDE-injected ABI optimization: the app APK MUST be debuggable and NOT testOnly,
so Android's normal installer can install it without ADB. Its ADB test APK remains
separate/testOnly. Inspect the actual manifest/ABI/signatures/hashes/source after
build. Never install the candidate over production or publish it as a Release APK.
CI debug signers can differ per runner; an older candidate then requires local
candidate-only uninstall/install and fresh candidate settings/OS permissions.
Production keys/data are unaffected. No signature or installer restriction bypass.

Local no-ADB route:

1. Install the exact-source verified candidate app APK through Android's normal
   installer. Actual install/unknown-source/uninstall prompts require the user.
2. Candidate Settings → Local capture QA. No LLM login/key/demo is required.
   Locally enable candidate accessibility, overlay and Android screen control;
   allow the blank Chrome/Notes fixtures under candidate App Access.
3. Request separate observation sharing and approve Android's default-display
   consent. Ordinary sharing is not upgraded. Stop an existing share first.
4. Select Chrome or Notes, and Screen observation. Optionally explicitly check
   saving one private historical test image. Arm ONE run, then show a NEW blank
   fixture with Samsung keyboard for at least10 seconds. The harness never opens
   a fixture or types/submits text. Do not use a page containing private content.
5. Own session ID+epoch are fixed at arm time. Wait is bounded to10min. Pause/X/end,
   lock, service replacement, projection epoch/lease change or cancellation stops
   without retry. Existing sessions are preserved. `onStop` permits fixture app
   switching; `onDestroy` cancels and cleans up only the same owned session ID.
6. Return to Local capture QA → View historical local result. CAPTURE_PASS means
   native scope/display/IME/empty focused editor/authority checks passed, NOT pixel
   placement/masking inspection. Review the image separately. No live coordinates
   or gesture authority are created. The current screen is never inferred from
   an old QA image.
7. Run Strict keyboard and Strict display separately to check the original Galaxy
   geometry refusals without fallback. REFUSAL_PASS requires the exact named
   geometry refusal; other blockers are not PASS. For After sharing stopped, first
   get a positive result on the SAME fixture/process, stop sharing in system UI,
   then arm the refusal check. The native screen_share_revoked result is required.
   This is a no-image admission check, not a complete race/cache-teardown proof.
8. Export the selected result ZIP only with the explicit local export action.
   It contains bounded metadata and, only if separately saved, one historical JPEG.
   The debug-only FileProvider exposes only the exports subdirectory; UUID export
   names prevent old URI grants from exposing new content, at most2 exports remain.
   Revoke old grants when pruning/deleting. No pixels/text/tokens enter logs, CI,
   task ledger or GitHub automatically. Keep physical images private and delete
   all QA evidence after review. Production live image cache/revocation is unchanged.

Waiting does not resuscitate revoked approval or adopt a new/resumed session.
Main-only signing/UI CI remains unchanged. A local success is not ADB comparison,
manual pixel review, full secure-window/rotation acceptance or production APK PASS.
Secure-window and rotation/refusal regressions remain required; record only cases
actually executed. If an assertion or setup is blocked, preserve that result.

ADB route, once Wi-Fi returns, use the app/test pair from the SAME artifact/SHA:

```sh
adb shell am instrument -w \
  -e class ai.closepaw.qa.PhysicalScreenObservationTest \
  -e physical_screen_observation true \
  -e physical_wait_ms 600000 \
  -e fixture_package com.android.chrome \
  -e capture_scope screen_observe \
  -e export_image true \
  ai.closepaw.capturecandidate.test/androidx.test.runner.AndroidJUnitRunner
```

Instrumentation still force-stops the target: rebind candidate accessibility
locally AFTER starting it and grant fresh observation sharing. Changed setup-only
status is bounded metadata. Once setup is ready, the SAME acceptance runner fixes
ownership and waits for the blank fixture. Previous180s setup deadline failures
are historical evidence, not capture failures. Repeat separately with Notes;
`capture_scope=keyboard|display` checks strict refusal. Optional
`verify_stop_after_capture=true` performs positive then stopped-sharing refusal
within ONE instrumentation process (the user stops via system UI when requested).
Standalone revoked checks cannot import a baseline across process death.

Private reports/JPEG are bounded under
`cache/capture-qa/runs/<local|adb>-<fixture-index>-<scope>/`. A new run clears its
old selected output before waiting, so stale PASS cannot stand in for a new run.
Local QA Compare requires a local+adb pair with full identical40-character source
SHA, version, fixture, scope, outcome/refusal and numeric visual contract. Different
capture IDs/timestamps/JPEG hashes are expected; byte equality is not required.
A match states **numeric contract only; pixels require separate review**.
Wrong source/fixture/geometry/authority or matching setup failures cannot count.

After real local image/position/masking and boundary acceptance, compare against
same-source ADB when available. Final review/requiredCI, protected normal merge,
exact-main Security+UI, existing production-key signing, one Release and independent
public APK/source/version/hash/certificate validation are still mandatory.
