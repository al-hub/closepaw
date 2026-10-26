# #165 IME compatibility continuation — 2026-10-26

Baseline main11c270255774670f5f70df472feb01e7d347d296; APK0.1.90/code91 independently reachable.
#165 OPEN. Initial control session ended/epoch0 after update. Final owned session cleanup is confirmed below.
Previous evidence remains versioned in issue165-final-acceptance-20261026.md and final-20261026.json.

## Delivered minimal implementation

Delivered0.1.91/code92 adds an explicitly requested **separate IME-window image**
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
Independent static code/security review found3 issues; each was corrected and re-reviewed.
No unresolved finding remains. This is not a formal security certification or device PASS.
The confirmed release/installation and actual physical blocker are recorded below.

#165 cannot Close: clipboard BLOCKED; Samsung candidate's installed-device behavior,
full two-app A/B/C matrix, rotation/alternate IME/X and performance remain NOT_TESTED.


## Confirmed delivery and actual acceptance disposition

PR [170](https://github.com/al-hub/closepaw/pull/170) merged. Source/tag
`4f6b58daf4216d67041af9306fd85c4ff4664f60`; PR and main tree exactly match
`c6775fcbf4fc72fae726340837435666a1f17c7f`.

| Gate | Confirmed result |
|---|---|
| Exact-head PR CI | Security40257707885 / UI40257707805 PASS |
| Raw PR XML | JVM2880 / Android14, failure/error/skip0; new7 tests PASS; UI8PNG |
| Independent static review | Coordinate/collision, semantic matching and layout verification findings corrected; unresolved=false |
| main CI | Security40258531590 (including optimized release) / UI40258531596 PASS |
| Signed Release | 40259082443 PASS, existing key, v0.1.91/code92 |
| Public APK |116788219bytes; SHA256 `4e473b13da560e2ed5725ddc8a1afbc5246366615b992523dd88f58155007b04` |
| Package / signer |ai.closepaw; v3 apksigner PASS; original certificate `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba` |
| Independent public bytes |Anonymous download hash/size match; ZIP CRC and both native runtime entries PASS |
| Tag |Annotatedf28315d39daa93834409ddd19a63956a8d769f3c resolves to exact source |
| Installation |Single request4a52ed9a-e40a-4f54-aca4-76c06742d5ab; durable installed/current0.1.91/code92 and independent get_status reachable |
| New IME device acceptance |**BLOCKED**: first app discovery and one follow-up after asking the user to unlock both returned device_locked; action_attempted=false |
| Clipboard device input |**BLOCKED** by the retained readable-provenance contract; no writes or Paste |
| Prior direct/Termux/visual evidence |**REUSED_EVIDENCE**, original0.1.89/0.1.90 versions in final-20261026.json |
| Full mixed input, rotation/alternate IME/X, gestures and performance |**NOT_TESTED** on this APK |

JVM artifact12623098964 SHA256
`18aab07ca7842f5fdfb70dd38be3e4e0be10474e1795893fdd3c67af6b262111`;
UI artifact12622832932 SHA256
`6f0879dc0a02af46b071712f7a00fd5d44cd80e6f31ed0752d3efe28621de98b`.
These independently re-summed XML are same-tree PR evidence; main CI success is
separately confirmed, not a second main XML re-sum.
Public verification artifact12624075363 SHA256
`4f1739e78efa770d00dbb2b2ef5be438cd795f63e7b583b07af9163771fe8958`.
The signature/manifest checks are the official public-download apksigner/aapt report
for independently hash-matched bytes, not a second local apksigner invocation.

New authorized session `e10fc69d-11c4-4e71-9cf7-c4a6a4bae40b` was started, then
ended by task9db3d2bf-5d1f-4de1-b6a0-dcac3779186e; independent status confirms
ended/epoch2. Original configuration22 was read, **no configuration mutation** was
submitted, so restoration/CAS was not needed. No clipboard write, terminal Enter,
search/send, existing-draft edit or native input attempt occurred.

**Close decision: #165 OPEN.** This is a delivered minimal IME proof/input implementation,
not an installed Samsung key-input PASS or complete A/B/C feature set. Visual layout
and Termux canvas key support remain implementation work; clipboard is an API/contract
blocker in this environment; device lock is a separate physical acceptance blocker.
Next minimum action is one unlocked-device session to observe the actual IME image and
corroborated labels, then one explicit synthetic key/effect check while preserving drafts.
Use the installed APK; do not rebuild/reinstall solely to repeat this blocked check.
Keep clipboard replacement/privilege/default-IME restrictions and do not close #165.
