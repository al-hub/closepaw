# #165 IME compatibility continuation — 2026-10-26

Baseline main11c270255774670f5f70df472feb01e7d347d296; APK0.1.90/code91 independently reachable.
#165 OPEN. Current control session ended/epoch2. No phone interaction or configuration changes yet.
Previous evidence remains versioned in issue165-final-acceptance-20261026.md and final-20261026.json.

## Minimal candidate

Candidate0.1.91/code92 adds an explicitly requested **separate IME-window image**
(`android.screen.observe`: `include_image=true,image_scope=keyboard`). The ordinary
application image remains scoped to its application window and cannot authorize a key.

`visual_key` requires a disclosed nonsemantic label candidate, the actual inspected IME
image, its exact visual ID and a confirmed image rectangle inside that native label's
bounds which contains its center. The injected point is always the native label center;
caller image coordinates only corroborate it. Duplicate parent/child labels of the same
purpose can coexist; conflicting labels (including Enter/Send/layout controls) covering
the actual point refuse. Existing clickable/isTextEntryKey admission is unchanged.
OS-selected IME identity, separate window security capture, live read/interact permission,
window geometry/pixels, foreground/input focus, session/epoch, overlay/occlusion and
sensitive-field guards are reused and rechecked. A new image never grants a node action.

This first route supports character, Space and Backspace only. It intentionally refuses
layout and submission controls; original proven semantic keys retain their own guarded
path. Visual-only nodes are excluded from automatic semantic keyboard matching/layout
selection. A single dispatch requires the existing exact text-effect verifier; acknowledgement
or unrelated screen change is not input PASS. Unknown results consume the observation and
are never replayed. Termux canvas input is not admitted by this new editable-field route;
its official InputConnection evidence remains separate.

## Clipboard — official platform boundary, not a fixed feature

Android's official contract restricts clipboard reads to the default IME or the app with
input focus:
https://developer.android.com/about/versions/10/privacy/changes#clipboard-data
https://developer.android.com/reference/android/content/ClipboardManager#getPrimaryClip()

The existing adapter requires another app's focused field and proves exact clipboard marker
and payload before Paste. ClosePaw is not the selected IME. A temporary ClosePaw foreground
screen would lose the read entitlement after focus returns to the target. Clipboard listeners,
hasPrimaryClip/getPrimaryClipDescription and a previously retained in-memory copy do not prove
current readable marker+payload at that boundary. Accessibility InputConnection is not the
default-IME role and does not grant a new clipboard read entitlement. No new permission or
supported cross-UID clipboard capability was found in these public API contracts.

Therefore this environment's clipboard path remains **BLOCKED** under the requested contract;
null remains ambiguous, not empty. No unchecked write, focus trick, default-IME replacement,
privileged permission or blind restoration was introduced. A supported readable target-side
integration or a changed explicitly approved contract would be a separate prerequisite.
This is not a universal assertion about every OEM/privileged environment, and is not a claim
that clipboard Paste has passed.

## Verification status

Tests were written before the implementation. Local Gradle wrapper could not download its
8.11.1 distribution (`java.net.SocketException: Network is unreachable`); there is no local
JVM RED/GREEN claim. Required exact-candidate PR CI must compile and run these tests.
New policy tests cover scope/permissions/focus/caret/owner, duplicate/conflicting labels,
submission exclusion and preserved semantic admission. Engine tests cover scoped image
proof, exact effect, IME changes and consumed observations after success/UNKNOWN.
Independent code/security review is in progress; final source/CI/review/device evidence
must be appended after observed completion. No released APK or device PASS is asserted here.

#165 cannot Close: clipboard BLOCKED; Samsung candidate's installed-device behavior,
full two-app A/B/C matrix, rotation/alternate IME/X and performance remain NOT_TESTED.
