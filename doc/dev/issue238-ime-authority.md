# #238 isolated IME surface coordinates

## Evidence and scope

The Galaxy API37 Chrome empty native input fixture has a 1440x2993 IME
surface at screen (0,127)-(1440,3120), while its accessibility touch region
is (0,1798)-(1440,3120). ADB numeric metadata joins accessibility window2's
WindowInfo Binder token to WM mClient, the exact WM VRI surface, and the
SurfaceFlinger source crop/output frame and identity-translation matrix.
Content inset1671 explains the touch subset; it is not a Surface origin.
This is a measured snapshot, not a hard-coded127 correction.

The candidate supports only keyboard-isolated capture with a freshly attested
integer translation, rotation0, unit scale, full source crop, unchanged identity
and exact buffer shape. App/display/screen_observe capture remains unchanged.
Samsung Notes acceptance belongs to #240. This document is not device acceptance.

## Runtime authority

- Requires the existing local remote-execution and generic-control permissions.
- Uses paired Termux Python and the already-running local ADB smart socket on
  127.0.0.1:5037; exactly one connected device is required. No ADB executable,
  server start, connection, pairing, key update, settings change or recovery.
- A fresh app-owned external-file nonce is read through that device. Only its
  SHA256 is returned; the nonce contents are absent from commands and logs.
  The temporary file is removed in finally. Wrong-device reads fail closed.
- The fixed packaged reader discards raw dumps and returns one bounded JSON
  object. Unknown/duplicate/missing identity chains, unsupported formats,
  transforms, crops or shapes are refusals. Titles never establish identity.
- Read before and after the native isolated screenshot. Require equal geometry,
  screenshot uptime inside the bracket, unchanged native window generation,
  explicit session, consent and editor policy. A dedicated HTTP400ms bound and
  finite job/poll budgets prevent the default shell transport's15s waits.
- Keep the whole native buffer. Mask outside the touchable subset; map keys
  only inside that subset. Actual visual_key revalidation reacquires metadata
  and verifies the original pixel hash and source geometry before its gesture.
- If no authority is available, the original exact-geometry path remains;
  mismatching buffers still produce no image/proof/gesture. No observation
  fallback, guessed offset, crop, tolerance or privilege bypass is introduced.

## Validation gates

Python fixtures exercise exact joins, variable translation, duplicate/missing
metadata, scale/rotation/crop/shape changes and bounded smart-socket framing.
JVM checks cover challenge/schema/mapping and stalled HTTP without replay.
The existing WindowVisualCaptureGeometryTest adds real-buffer translated
capture/masking, stale timestamps and unknown-job/nonce cleanup checks.
Exact-head CI, independent final review, main CI, original-key signing/public
APK verification and Galaxy Chrome visual-key acceptance remain required.
Refs #238; do not auto-close on merge.
