# #145/#146/#147 and #150 acceptance automation — v0.1.80 / code81

Candidate implementation; exact-HEAD CI, release and physical results are recorded separately.
Previous signed APK remains v0.1.79/code80 until a new successful release is verified.

## Changes and preserved safety

- #145: bounded unlock-event visibility retries bridge the broadcast/keyguard-state race.
  Retry only restores existing active/paused UI; epoch change, screen-off and service disposal
  stop it. Monotonic restore ID logs distinguish trigger, keyguard wait, request and actual
  attachment. Attachment is not proof of first visible pixel. Phase labels use actual state.
- #146: island WindowManager frame explicitly uses physical screen coordinates rather than
  a system-bar-inset frame; actual X rectangle has priority, cutouts are excluded, full measured
  bounds remain clamped. Request43,59 vs screen43,126 regression is exercised with a real
  WindowManager root twice on the emulator. OEM Galaxy reproduction requires physical check.
- #147: replace per-byte locale formatting in SHA hex generation with equivalent fixed hex
  encoding. SHA-256 inputs/output and fresh observation/session/consent/foreground checks
  are unchanged. Invocation-local cancellation evidence reaches durable get_task only after
  structured handler cleanup. Local stopped acknowledgment and uncertain external effect are
  separate; partial effects remain outcome_unknown and never become successful replay.
- #150: [catalog and operating guide](../../qa/acceptance/README.md), impact selection,
  fail-closed evidence/report validation and matched-cohort statistics. Full release CI stays.

## Historical evidence and fresh acceptance

v0.1.79 local calculator 10/10 mean10,963.9ms, ordinary unlock/position and physical X stop
remain historical evidence. Screen-off unlock delay and Y+67px are unresolved physical
observations until the candidate runs on the Galaxy. Existing X PASS does not prove the new
external terminal contract. Original full raw evidence and issue comments remain unchanged.

A 10+10 host hash benchmark is produced by JVM tests. Android baseline/improved matched
10+10, Voice/client idle, transport latency and physical X outcome on code81 are NOT_TESTED
until an authorized device execution path is available. Do not report host speed as Android.

## Work environment boundary

Initial phone status returned0.1.79/code80. Discovered session.status invocation was refused:
execution disabled locally. No retry, session restart, permission change, device gesture or
installation was performed on that refusal. Local Gradle wrapper distribution download was
unreachable, so Kotlin/JVM/UI compilation is delegated to the existing GitHub CI environment.
This does not block the original-key GitHub release path once CI passes.

## Required final checks

- Exact-source PR Security CI and Premium UX UI, then main Security CI success before signing.
- Existing certificate d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba,
  manifest81, two runtime ABIs, source/tag, checksum and public download independent verification.
- Single install request only if available; durable installed/current then independent app status.
- #145 one OFF/unlock physical display check with restore telemetry; #146 exact top coordinate;
  #147 matched Android comparison and physical X only to cover changed safety/result path.
- #145/#146/#147 stay OPEN until those core conditions pass. #117/#76 transfers already exist;
  no new transfer issue or duplicate notification issue is needed.

## Time-saving evidence

This Work removes repeated preparation/classification and structures reusable historical
proof. It keeps full CI and current essential physical checks. No comparable end-to-end
engineering-time baseline has been measured, so no percentage/time saved is claimed.
