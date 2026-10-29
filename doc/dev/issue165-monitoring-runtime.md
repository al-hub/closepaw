# Issue 165: device-approved monitoring runtime (review candidate)

This change connects monitoring to production operation registration, an unexported Android approval Activity, a lifecycle-bound runner, the existing Termux bridge manager and a final Android service-start guard. It does not fix projection clock interpretation or restore terminal input. No final release or physical-device acceptance is claimed.

## Authority and lifecycle

- `closepaw.monitor.start` requires an explicitly active, unlocked control session, remote execution and generic control settings. Target and action are fixed to the local Termux bridge and `termux_bridge_start_once`; duration is 10–300 seconds. No remote command or permission argument exists.
- The request creates a 30-second pending device approval. Opening the Activity does not approve it. Only its Approve button activates monitoring. Cancel, expiry, session changes, X, screen-off, projection revocation, settings revocation and MCP service destruction invalidate unused authority. Process death drops all authority; no restore or automatic replay exists.
- Pending device consent blocks generic Android UI control, including cached/native dispatch boundaries. A concurrent running local agent also prevents admission. The consent window is FLAG_SECURE, unexported, and filters obscured positive-button touches.
- Probes use the existing passive `diagnosticSnapshot`. A healthy bridge is observed every two seconds within the approved lifetime. Unverified, unauthenticated or unsupported observations stop rather than recover.
- Only an offline bridge with verified RUN_COMMAND availability is eligible for the existing fixed bridge-start command. The monitoring path does not invoke setup, installRecovery, token writes, apt, user-job replay or manager-owned detached restart jobs.
- Authority is consumed at the actual Android startService/startForegroundService boundary, under the same lock used by Stop. Exceptions and uncertain results cannot replay. Cancellation before the boundary prevents service start; cancellation after dispatch cannot undo an external effect.
- A fresh passive query supplies post-check health. `recovery_attempted` reports dispatch attempts, not successful repair. A ready post-check does not prove that the recovery caused readiness. New consent is required for any future attempt.
- `closepaw.monitor.status` exposes bounded state, attempt and post-check vocabulary; no command, token, exception contents or screen data. Stop revokes authority without claiming an already dispatched command was undone.

## Test scope

JVM coverage includes pending-consent inactivity, approval identity and expiry, lock/permission/expiry rejection, Stop/dispatch serialization, unknown outcomes, runtime cancellation, independent post-check, no detached manager dispatch, and refusal of unverified health. An adapter test checks that denied authority calls neither Android service-start method. The UI suite additionally registers the real operations, opens the real consent Activity, verifies pending-consent Android control denial, approves in the throwaway emulator and exercises runner → manager → guarded dispatch → independent post-check with an injected bridge. This fixture does not start a real Termux process and is not physical-device acceptance.

## Current device constraints

The connected Galaxy reports ClosePaw 0.1.98/code99. Its Termux job environment reported `TERMUX_VERSION=googleplay.2026.10.09` (task `f3fb5682-83dc-424a-88bd-a2d6ffb139dd`). The Play release source is https://github.com/termux-play-store/termux-apps/tree/termux-app-googleplay.2026.10.09 . Its TerminalView maintains separate selection state, and its InputConnection sends text after stopping selection. Empty IME surrounding text cannot prove selection absence. P1 remains blocked; no guard was removed. The Play variant may lack RUN_COMMAND; the monitoring runtime must report unsupported recovery rather than bypass this limitation. Existing Termux-side supervisor behavior remains separate.

## Projection evidence and next diagnostic

Installed APK diagnostic task `6469b394-8954-45ec-9a0b-5850bbd277e8` contains `projection_frame_acquire: timeout`, `last_observation=stale_frame`, null_reads=8, stale_reads=50, timestamp_increasing=49, uptime_age=0_to_1s, elapsed_age=over_60s, lease_active_after=true. Thus frames arrived and were rejected before packing/encoding; this is not a no-arrival diagnosis. Source-clock contract remains unverified.

Android Image.getTimestamp explicitly makes the timebase source-dependent. AOSP virtual-display implementations and Android SystemClock definitions explain a plausible monotonic/boottime disagreement, but do not prove the Samsung producer contract. Do not switch to uptime or increase freshness thresholds based on these age buckets.

The candidate adds only two bounded metadata samples per capture attempt (first/last source timestamp, acquisition/receipt elapsed time and uptime, plus consent generation). Raw pixels and screen text are absent, and freshness enforcement is unchanged. These samples are needed to compare a controlled, privacy-free screen change in the same session after a reviewed diagnostic build. They are not P0 resolution or image-delivery success. No same-failure capture was repeated without code/evidence change.

## Release gate

P0 source timebase and actual image delivery, P1 safe terminal selection/insertion, exact-final-head Security/UI results and independent review remain mandatory. This candidate must not be merged or shipped as the completed #165 solution. The final APK installation and full physical-device acceptance remain a separate phase.
