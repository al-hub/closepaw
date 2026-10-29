# #165 P0 ImageReader clock provenance — 2026-10-30

Investigated base `35f569a11ade121206eecdf34973966fe3b70881` on
`work/165-p0-source-contract-20261030`. Local diagnostics/test work only;
no push, merge, release, version change, device control or installation.
P0 functional acceptance remains OPEN.

## Provenance and limits

The application creates an RGBA_8888 ImageReader with two buffers and supplies its
Surface to MediaProjection.createVirtualDisplay with AUTO_MIRROR. It does not set
producer timestamps or select a timestamp clock. ProjectionImageLease reads
Image.timestamp in nanoseconds and divides by 1,000,000 for the existing elapsed
freshness checks; this arithmetic is unit conversion, not clock conversion.

Primary sources inspected on 2026-10-30:

| Source | Established fact | Limit |
| --- | --- | --- |
| [Image.getTimestamp](https://developer.android.com/reference/android/media/Image#getTimestamp()) | Nanosecond timestamp; meaning and timebase depend on the producer. | No universal MediaProjection elapsed/uptime contract. |
| [ImageReader JNI](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/jni/android_media_ImageReader.cpp), observed blob `effa92c5f2fad453e964d38fea40def3a257ec3b` | ImageReader_imageSetup copies BufferItem.mTimestamp into SurfaceImage.mTimestamp. | Acquisition does not generate a new capture timestamp or attach clock identity. |
| [VirtualDisplaySurface](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/services/surfaceflinger/DisplayHardware/VirtualDisplaySurface.cpp), observed blob `384f7b22c7bf1a3ead4382829845d41c8a05b16d` | HAL-display onFrameCommitted queues recomposed output with systemTime(); GPU virtual-display queueBuffer forwards the input. | Different producer paths exist; the target OEM path has not been identified. Queue/commit time also does not prove screen-content age. |
| [Surface](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/libs/gui/Surface.cpp), observed blob `e41f9bbf43030e2119b3b7d9e698b0ce3cd66951` | Automatic timestamps are generated with SYSTEM_TIME_MONOTONIC. | Conditional on automatic timestamps; explicit timestamps need separate provenance. |
| [Timers.h](https://android.googlesource.com/platform/system/core/+/26d47f2671e67968ea408417f03844681faf9455/include/utils/Timers.h) | systemTime defaults to SYSTEM_TIME_MONOTONIC; BOOTTIME includes suspend. | Historical source evidence for the default, not verification of the installed OEM binary. |
| [SystemClock](https://developer.android.com/reference/android/os/SystemClock) | Uptime excludes deep sleep; elapsed realtime includes it. | Both being monotonic does not make them interchangeable. |

The `refs/heads/main` URLs are moving AOSP references; blob IDs identify the
observed file contents. These files are implementation evidence, not an OEM
build match. Attempts to inspect the Android 16 release-tag versions and the
RenderSurface source through the web tool failed; the GPU caller's timestamp
selection was not verified. Do not represent this partial trace as an end-to-end
Android 16 contract.

ImageReader's [video-encode usage documentation](https://developer.android.com/reference/android/media/ImageReader#newInstance(int,int,int,int,long))
discusses uptime-like timestamps for CameraDevice plus USAGE_VIDEO_ENCODE. That
source/usage-specific rule does not establish this RGBA MediaProjection path.

## Existing device evidence

The historical samples embedded in MediaProjectionFramePolicyTest describe a
Galaxy Android 16 observation on v0.1.99; they were not recaptured in this task.
Integer-millisecond comparisons are:

| Source ns | Receipt elapsed ms | Receipt uptime ms | Elapsed age ms | Uptime age ms |
| --- | --- | --- | --- | --- |
| 596111006200151 | 792317482 | 596111022 | 196206476 | 16 |
| 596112508350502 | 792318976 | 596112516 | 196206468 | 8 |

This supports an uptime-like hypothesis and explains the current elapsed-age
refusal. It does not distinguish a source-clock contract from numerical agreement,
nor prove a fresh screen change, suspend behavior or ownership.

## Safe diagnostics and tests

Keep the existing two endpoint metadata samples per attempt (source timestamp,
acquire-start elapsed, received elapsed/uptime, sample-finish elapsed), consent
generation, bounded counts/categories, and `source_clock_contract=unverified`.
The samples bracket acquisition/clock reads, not production. Diagnostics retain
at most 64 events via ControlDiagnostics; no additional samples, pixels, screen
strings, package names, tokens, fingerprints or exception messages were added.
Corrected one stale source comment that incorrectly said no raw timestamps were
retained. All executable production behavior is unchanged.

Strengthened lease tests check sample field allowlists, endpoint-only retention,
no fabricated samples after empty reads, acquisition/clock-read bracketing,
stale refusal without plane access, and stale final-delivery refusal even when
the timestamp numerically matches uptime. A new policy counterexample shows why
receipt-time offset mapping is unsafe even if the source really is uptime:

- A buffer queued at uptime 89,500 / elapsed 99,500 waits across 60,000 ms suspend.
- Receipt at uptime 90,000 / elapsed 160,000 gives offset 70,000.
- Adding that offset to the old source timestamp yields 159,500, an apparent age
  of 500 ms, although actual elapsed age is 60,500 ms.

The existing elapsed admission, post-acquire and final-delivery bounds remain;
consent generation, revocation, geometry, accessibility ownership and region
masking paths are unchanged. No UPTIME selection, offset conversion, age-threshold
increase, receipt-time freshness substitution or fallback was implemented.
Existing elapsed comparisons are preserved compatibility behavior; they do not
become proof of an unverified source timebase merely because they reject these
historical samples.

## Exact blocker and next evidence

**Clock-based admission changes are blocked by the absence of a trusted,
build/path-specific source timestamp contract and a conservative capture-time
elapsed-age bound across suspend and queued buffers.** AOSP implementation
fragments and uptime-matching device samples establish neither. Even establishing
MONOTONIC alone would leave capture-time offset/suspend ambiguity unresolved.

The next investigation needs producer-path/build evidence covering GPU/HAL and
timestamp meaning, plus a reviewed design that rejects ambiguous pre-suspend or
queued buffers. A controlled privacy-free screen change using the existing
bounded samples could test a hypothesis, but cannot authorize a universal clock
mapping. This task does not run that experiment or request lock/suspend activity.
Independent review and exact-head Security/UI validation remain prerequisites
for any later integration; local self-review is not independent review.

## Validation

Normal targeted Gradle invocation attempted:

```sh
./gradlew :app:testDebugUnitTest --offline \
  --tests ai.closepaw.platform.virtualdisplay.ProjectionImageLeaseTest \
  --tests ai.closepaw.platform.virtualdisplay.MediaProjectionFramePolicyTest \
  --console=plain
```

It failed before tests: `SDK location not found`; this checkout has no configured
Android SDK (`ANDROID_HOME` or local.properties sdk.dir). No Android build, lint,
UI, full Security CI or device acceptance PASS is claimed.
