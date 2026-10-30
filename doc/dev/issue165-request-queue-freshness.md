# #165 request-local compositor output freshness

## Evidence and invariant

Galaxy S24 Android 16 request `4c17fc84bd0e4b4995ff4ed2b5a9cb95` received 57 images, no null reads, but rejected all 57. Image timestamps were about 5 ms from uptime and about 56 h 46 min from elapsedRealtime. This proves the direct comparison was invalid on this device. It does **not** prove a portable Image.timestamp clock contract.

Official Image.getTimestamp specifies source-dependent timebase:
https://developer.android.com/reference/android/media/Image#getTimestamp()

The replacement does not infer or translate that clock. Each capture creates a **new ImageReader**, hence a new BufferQueue, and attaches its Surface to the existing single VirtualDisplay. The session's old reader is geometry-only and is never a pixel fallback. The elapsed lower bound is sampled **before the new reader is created**, not when any frame arrives. A compositor output acquired from this new queue cannot have been queued before that queue existed.

Source basis:
- ImageReader native initialization creates a new buffer queue/consumer:
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/jni/android_media_ImageReader.cpp
- SurfaceFlinger is the producer of virtual-display output queues:
  https://source.android.com/docs/core/graphics/layers-displays
- VirtualDisplaySurface draws/dequeues output from the sink and queues composed output to it:
  https://android.googlesource.com/platform/frameworks/native/+/80c48461e22595397a7f6be038bfa7457db6a101/services/surfaceflinger/DisplayHardware/VirtualDisplaySurface.cpp
- VirtualDisplay.setSurface changes the output sink without a second createVirtualDisplay:
  https://developer.android.com/reference/android/hardware/display/VirtualDisplay#setSurface(android.view.Surface)

This bounds the age of a **compositor screenshot output**, not the last draw time of each source app layer (a static screen can legitimately remain unchanged). This is an inference from queue construction and the compositor output contract, not an OEM timestamp assertion. Android/OEM implementation acceptance remains separate from mocked JVM tests.

## Boundaries

- Capture and post-acquire age: at most 1000 ms from queue creation lower bound, measured using elapsedRealtime (includes sleep).
- Delivery expiry: at most 10000 ms from that same lower bound. Receipt or producer timestamp never extends it.
- Every attempt has a fresh reader identity; simultaneous capture is refused. Cleanup is identity-checked so a late old cleanup cannot detach a newer sink.
- Cancellation, timeout, attach failure and scope teardown close the request reader. Partial attach failure permanently closes the scope. No receipt-offset fallback.
- Source timestamps (same/increasing/decreasing/future) are bounded diagnostic metadata only. Unverified source clocks remain marked unverified.
- OS revoke/session replacement and capture geometry change invalidate the lease. Resize currently fails closed and requires a new screen-sharing round; transparent rotation recovery is **not claimed**.
- Existing isolated accessibility ownership, masks, secure-screen refusal and final image revocation checks remain required downstream.

## Bounded polling settings

Only timeout 1500–3000 ms and poll interval 25–100 ms are adjustable. Each attempt snapshots one revision. These values do not change freshness, delivery expiry, queue proof, permissions or masking. The CAS update requires the actual explicit ACTIVE server session and matching ID in addition to caller authorization. Session stop/pause and durable commit serialize on the same session monitor.

Persistence now uses one AtomicFile document rather than SharedPreferences: SharedPreferences may alter in-memory values even when commit returns false. Invalid/missing documents load safe defaults. Rollback is an authorized new revision restoring default values, never revision rewind. File IO is dispatched off Main. No AI tuning/profiles or #203 implementation is included.

## Verification status

JVM cases cover normal new-queue admission, old/suspended queue refusal, elapsed regression, untrusted source stamps, expiry, geometry, revoke/session loss, timeout cleanup/bitmap race, concurrent requests, queue identity/attach failure, effective polling values, tuning bounds/CAS/restart/rollback/failed storage/authorization/stop race.

Exact-head CI and independent review must be recorded in #165 before merge/release. Galaxy S24 capture/IME, suspend, stop-sharing/reapproval, rotation and P1/P2 acceptance remain NOT TESTED for the next APK. #165 remains OPEN.
