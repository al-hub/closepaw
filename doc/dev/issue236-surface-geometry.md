# #236 Surface geometry investigation — 2026-11-14 KST

## Disposition

**BLOCKED for a production correction and Release APK.** Keep #236 OPEN.
No proven buffer-to-screen origin/transform is available for the mismatching
Samsung IME surface. Production capture, ownership and consent code remain
unchanged. A test-only Draft PR preserves refusal behavior; it must not be
merged as a versionless release workaround or shipped as a diagnostic APK.

Baseline: published v0.1.110/code111, source/tag
`9c8eaacd6f326edb23584b5149b54997d30a3f27`; inspected main
`edecc0b2c5e13fdb6246149c1a98687aa25b4dd6`.
The entire `app/src/main`, `app/src/test` and `app/src/androidTest` trees are
unchanged between these refs. No related #236 PR existed at inspection.

## Reused physical evidence, not a new device test

| Request | Result | Confirmed failure stage |
| --- | --- | --- |
| Chrome, keyboard-only | buffer 1440×2993, bounds (0,1798)–(1440,3120), image 0 | direct window bitmap geometry comparison |
| Samsung Notes, full display with keyboard | same buffer/bounds, image 0 | MediaProjection acquired; isolated-window geometry comparison failed |
| Historical landscape | buffer 2826×1335, bounds size 2826×773 | different height excess; no portrait fixed inset can be justified |

The OS-selected IME ID matched between the two recent observations. This is
strong circumstantial evidence for the same IME failure in Notes, not an atomic
join of the failing event to that ID: `visual_geometry` does not record the
failed window ID, role or native buffer origin. No OEM transform is inferred.

Source paths at the inspected SHA:

- `WindowVisualCapture.kt`: window `capture()` requires exact bitmap/bounds
  dimensions; display `capture()` acquires the projection and calls
  `capturePixels()` for every planned surface. An isolated failure returns
  `display_isolated_` plus its reason. No window-to-display fallback occurs.
- `AndroidControlPort.kt`: `displayPlan()` associates OS window IDs, regions,
  layers and visible disjoint parts; `captureVisual()` pins and revalidates
  consent epoch, session, editor privacy, IME/input target, display generation,
  rotation and plan. These checks do not expose the mismatching Surface origin.
- `OwnedPixelPolicy.kt`: compares pixels at an already established coordinate
  mapping; equal colors cannot establish that mapping or window identity.

## Platform contract and missing proof

Official references:

1. [AccessibilityWindowInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo#getBoundsInScreen(android.graphics.Rect)):
   bounds are the bounding rectangle of `getRegionInScreen`; the region is the
   window's **touchable** region, not a promise of Surface buffer dimensions.
2. [AccessibilityService.takeScreenshotOfWindow](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,java.util.concurrent.Executor,android.accessibilityservice/AccessibilityService.TakeScreenshotCallback)):
   captures a window selected by accessibility window ID. It does not document
   a buffer-origin mapping to that touchable region.
3. [ScreenshotResult](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.ScreenshotResult):
   exposes HardwareBuffer, ColorSpace and timestamp, not a screen origin or
   Surface-to-display matrix.
4. [AOSP Android 16 source](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/view/AccessibilityInteractionController.java):
   `takeScreenshotOfWindowUiThread` captures the ViewRoot SurfaceControl layer
   with `childrenOnly=false` and the process UID, after the secure-window check.
   It supplies no crop equating the layer to the accessibility touchable region.

**Confirmed application failure:** the strict geometry gate rejects these
observations. **Contract mismatch:** treating a touchable rectangle as if it
must be the complete Surface geometry is not generally guaranteed by Android.
**Unconfirmed:** Samsung's actual Surface origin, transform/insets, and the
complete causal reason for these particular buffer dimensions. AOSP alone is
not Samsung implementation proof. Same width, a height difference, visual
similarity, or a root-node rectangle cannot fill that gap.

No crop, resize, tolerance, hard-coded inset, privileged/hidden API workaround,
capture-source substitution or weakened ownership check is introduced.

## Partial-support candidate, not implemented

A future explicit app-only display result could exclude all unverified IME
contributions and preserve only app pixels independently verified at their
existing established coordinates. It would need complement masking, no IME
coordinates in `disclosedRegions`, explicit partial-coverage metadata and the
same session/consent/window-change revalidation. Geometry mismatch must not
be converted into unconditional `continue`, especially for other errors or
application windows. The current `IsolatedVisualSurface` has no role field;
dimensions alone cannot safely select which surface to omit.

This is not a fix for Chrome keyboard-only capture and cannot satisfy the
complete #236 delivery goal. No partial production change is made here.

## Automatic regression scope

`WindowVisualCaptureGeometryTest` invokes the real capture implementation on
the existing API35 CI emulator, using generated blank HardwareBuffers and a
mocked service/projection source. It never connects to a live accessibility
service or requests MediaProjection. Cases:

- an exact synthetic small window returns a proof (positive control);
- portrait mismatch returns no proof and no display fallback;
- landscape dimensions also refuse (the test's origin is synthetic, unknown
  OEM coordinates are not asserted);
- a projection frame can be acquired successfully while isolated IME geometry
  still rejects the complete display result;
- native buffers close on these outcomes; the projected bitmap is recycled.

The existing UI workflow allowlist explicitly includes this class. Passing
these tests means **refusal preserved**, not Samsung capture repaired. No
production fix is proposed, so a fix RED→GREEN claim is inapplicable. Existing
OwnedPixelPolicy, display-change, keyboard-replacement, editor-privacy,
session/revocation and cache/transmission tests remain the safety baseline;
an emulator result cannot establish OEM or physical acceptance.

## Evidence needed before production work can resume

Obtain an authoritative, window-identity-bound Surface-to-display transform for
the actual Samsung IME, including native frame/buffer bounds, surface insets,
rotation/scaling and capture-time correlation. Possible future investigation:
an explicitly authorized, read-only Android debugging session extracting only
numeric window/layer metadata, correlated by token/ID, on the blank fixtures.
Debugging access and sufficient data are not currently established; do not
assume dumpsys output alone provides a complete transform or a runtime-safe
public API. A controlled reference IME may falsify hypotheses but cannot prove
the Samsung mapping. Determine a supported production mapping before cropping.

Repeated capture with the same current metadata cannot establish the origin.
No phone action or new permission is required from the user in this Work.

After a justified correction and independently verified signed APK exist, the
minimum later physical checks are (1) Chrome keyboard-only capture and (2)
Samsung Notes full-display capture with the keyboard. Verify returned pixels,
coordinate mapping and masks, then relevant IME/rotation/revocation boundaries.
Both are **NOT_TESTED** in this Work; no installation, screen sharing, device
control, settings change, Microduck use or modification of #153/#235/#165 occurs.
