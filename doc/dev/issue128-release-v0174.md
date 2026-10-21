# Issue #128 — v0.1.74 / code 75 delivery

This work continues PR #131 from the other development conversation. The installed
baseline was v0.1.73 / code 74. The earlier #118 fixes and accepted device checks
remain valid; no locked-screen, camera, Wi-Fi/SSH, basic calculator, browser or
Gemini Q&A acceptance was repeated.

## Implementation

- The legacy remote-control and local agent-task X hosts arbitrate visibility.
  Either visible emergency X ends remote control and requests local agent shutdown.
- The existing Compose island is the only status window. Remote-only control also
  supplies live status to this island, avoiding both duplicate bars and missing
  bridge-only status. The removed confirmation button is no longer mentioned.
- Read-only island taps expose no controls. Long press enables haptic drag;
  screen-coordinate deltas account for the moving window. Only acknowledged
  WindowManager updates are persisted. X and island positions use separate stores.
- Both current X and island windows participate in owned-overlay security checks
  and reject generic AI touches that would hit the control surface.
- The unrun candidate workflow with different signing-secret names was removed.
  The existing Signed Release pipeline uses the original CLOSEPAW_* keystore,
  checks certificate continuity, builds both tunnel runtimes, and independently
  verifies the published APK, manifest and SHA-256. Released tags remain immutable.

## Verification boundary

`ControlOverlayWindowTest` runs only in androidTest. It enables the real app
accessibility service on the emulator and injects MotionEvents directly into the
attached X and Compose island views, checking their actual WindowManager params.
It covers ten movements per surface, durable coordinates, X re-display position,
single-X arbitration while both kinds of session are present, and stop cleanup.
This direct test injection does **not** prove physical input delivery on Samsung
or accessibility dispatchGesture delivery to the service's own overlay. No
production operation can reposition the emergency X on behalf of an AI.

Physical Samsung acceptance remains open: long-press/drag ten times per surface,
vibration, single visible X/island across app switches and reconnects, outside
touch/keyboard pass-through, collision avoidance, rotation, folding, TalkBack and
service recreation. Do not close #128 or mark those tests passed based on CI.

## Delivery evidence

- PR #131 merged as `c124f3f86994664c9685b883cb31e1796b38c894`.
- Final candidate `12f803ef28ef1589a092a5c34d3c3c4bb4bda076`:
  [Security CI 39607783934](https://github.com/al-hub/closepaw/actions/runs/39607783934)
  and [Premium UX UI 39607783952](https://github.com/al-hub/closepaw/actions/runs/39607783952)
  completed/success. UI: six tests, zero failures, including the real overlay test.
- An earlier compile failed because a delegated property setter collided with an
  explicit JVM method; the backing property was renamed. The first window test
  passed both ten-move loops, then checked attachment before the new X's first
  traversal. It now waits off Main and tests short DOWN/UP stop events. Failed
  candidate runs are not evidence of successful delivery.

- Main [Premium UX UI 39608950568](https://github.com/al-hub/closepaw/actions/runs/39608950568)
  also completed/success on the merged source.

- Main [Security CI 39608950694](https://github.com/al-hub/closepaw/actions/runs/39608950694)
  completed/success.
- [Signed Release 39610112952](https://github.com/al-hub/closepaw/actions/runs/39610112952)
  completed/success, including release unit tests and independent anonymous APK
  download/signature/manifest/checksum/native-runtime verification. Public
  verification artifact: 12350816441.
- [Release v0.1.74](https://github.com/al-hub/closepaw/releases/tag/v0.1.74),
  [signed APK](https://github.com/al-hub/closepaw/releases/download/v0.1.74/closepaw-aa-bridge.apk):
  116,539,549 bytes; SHA-256
  `e408bc8ed554acfafb91deef1ac8c8067da9e68aeaf2167c146ff6a265429ada`.
  Manifest package `ai.closepaw`, version 0.1.74/code75; original signer SHA-256
  `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`.
  Immutable APK tag/source: `c124f3f86994664c9685b883cb31e1796b38c894`.
- Exactly one update task `96622026-8dc2-49f3-9da9-a78e3ecae77b`.
  Task acceptance/installing was followed by independent durable status:
  installed/current 0.1.74/code75. `get_status` independently confirmed the
  responding app also runs 0.1.74/code75. Install-source and remote-update
  permissions remain enabled; no re-registration or duplicate update request.
- Device verification in this work is installation/version/connectivity only.
  The physical Samsung checks above remain UNVERIFIED and #128 stays OPEN.

This documentation does not change the immutable APK source commit.
