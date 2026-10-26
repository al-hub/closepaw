# #165 explicit display capture — 2026-10-26

Baseline main `986c411e3e0f4ad771626225cfeec987421f1993`; independently responding
phone `0.1.91/code92`. PR166/168/169/170 merged. Existing device evidence retains
its original source/version. #165 remains OPEN until all required physical gates pass.

## Source audit and minimum change

The generic control path used `WindowVisualCapture` →
`AccessibilityService.takeScreenshotOfWindow`, not the unrelated platform
`AccessibilityScreenshotCapturer`'s display API. The old nullable result collapsed
native API/timeout, raw geometry, conversion/compression and post-capture freshness
failure. No historical device result identifies which of those branches failed.

`McpJsonRpcHandler.toolResult` attaches `VisualImageCache.imageFor(structured)` as a
separate MCP image content block. `data.image_included=false` describes the metadata
ledger; only the actual content block proves image delivery. No screen is persisted
in the task ledger or this public report.

PR171 adds explicit `image_scope=display`; app and keyboard scopes remain. There is
no automatic fallback following a window/API/policy denial. Native stage failures
have fixed non-content codes (including native secure/invalid-window/permission/
interval failures, buffer geometry, conversion and post-capture changes).

A subsequent review found that the interactive-window list is not a complete
compositor surface inventory: a static untouchable overlay can be unreported.
The final route therefore independently captures every disclosed app/IME region
using takeScreenshotOfWindow and compares every output pixel against that isolated
source at its exact OS coordinate. Returned display pixels must equal isolated
owned pixels; missing sources/geometry refuse without image delivery. Differing compositor pixels are blacked out; only the exact matching pixel runs are disclosed. The proof retains disjoint verified regions, and coordinate mapping refuses ANY target rectangle containing an unverified pixel. Native recapture also requires identical verified regions; masking is never input authority.
Window events/masking are defense in depth, never sole ownership authority. This
may refuse animated screens or an IME whose isolated screenshot is unavailable;
that outcome remains BLOCKED with a precise reason, not a raw display fallback.

The display route reuses the capture, proof/cache, selected-window security scan,
OS-selected IME, app configuration, explicit session/epoch and input-effect guards.
Only the default display with an unambiguous complete window set is supported.
Unowned application/accessibility-overlay windows refuse. System UI and the app's
own control overlays, plus all pixels outside OS-certified app/IME regions, are
blacked out before hashing/encoding. The image keeps the whole display coordinate
frame, with source dimensions, rotation, app/IME bounds and masking metadata. Source
buffer dimensions must exactly match native display dimensions. No inferred insets
or coordinate correction is admitted.

Independent review of315adff found2 defects: a transient overlay between endpoint scans, and a stale window-only key provenance guard. The follow-up counts every window state/set event (including null/own owners), rejects intervening generations across capture/validation/cache/dispatch, and retains a separate source IME window ID for the native key guard. Matching endpoint geometry alone is insufficient. Added regression tests cover both findings. A follow-up review found that the passive full-screen transparent decoration was subtracted as an opaque rectangle. It now supplies a conservative render mask for its perimeter/target/ripple pixels; bounded controls still mask their full rectangles. The native fixture asserts a visible app interior in the full-display image.

A display image authorizes only the existing native-label-center `visual_key`
route. General app visual gestures still require the app-window proof. Key dispatch
recaptures the same scope and compares exact processed pixels, geometry, rotation,
window context, IME/field and session. Semantic key admission is unchanged. Only
character/space/backspace are supported on this minimal visual route; layout keys
and Termux canvas keys remain separate incomplete requirements. UNKNOWN consumes
the observation and never retries/falls back.

## Clipboard platform boundary

Official Android documentation restricts clipboard reads to the default IME or
currently focused app. ClosePaw's accessibility InputMethod is not a default-IME
role. An app-focus detour or retained copy cannot prove readable marker+payload
at the target's Paste boundary. Under the existing contract, no supported generic
cross-app solution has been established. Clipboard remains BLOCKED; no unreadable
clipboard is assumed empty, overwritten or blindly restored. No IME/permission
replacement or privileged bypass is introduced.

## Validation and delivery

- Tests were added before implementation; local Gradle distribution fetch failed
  with network unreachable, so no local JVM RED/GREEN claim.
- Local Python suite:43 PASS. `git diff --check`: PASS.
- Added pure policy/native-code mapping/full-display coordinate/no-replay tests.
- Existing Android fixture now exercises actual display screenshot bytes/decoder,
  source display metadata and cache revocation across lock/unlock. The positive display fixture hides its observed IME once in the throwaway emulator; the default emulator IME whole-tree scan reported authentication_boundary and is not claimed as an IME-image PASS. The unreadable password IME still denies display images, and the original official InputConnection password-refusal assertion remains. This is emulator
  evidence only when CI succeeds, not Samsung/actual Termux acceptance.
- Required exact-commit Security/UI CI, independent code/security review, main CI,
  original-key signed release, public bytes verification and phone installation
  are pending. Candidate `0.1.92/code93` is not yet the installed version.

## Physical gates / current disposition

Actual display image+Samsung IME proof, single character effect, affected physical
X/cancellation, rotation/alternate IME and full two-app A/B/C matrix are NOT_TESTED
on the candidate. Clipboard is BLOCKED. Historical direct and official Termux
InputConnection PASS remain REUSED_EVIDENCE at their original sources. No new
phone control session, settings mutation, input, clipboard write or install has
occurred during source development. Initial independent session status: ended/epoch10.
Do not close #165 from a PR, CI, API existence or refusal alone.

## Official contracts

- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
  (`takeScreenshot` display versus `takeScreenshotOfWindow`, capability and error codes)
- https://developer.android.com/about/versions/10/privacy/changes#clipboard-data
- https://developer.android.com/reference/android/content/ClipboardManager#getPrimaryClip()
