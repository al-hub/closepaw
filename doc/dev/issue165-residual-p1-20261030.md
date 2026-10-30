# #165 residual P0/P1 — 2026-10-30

Baseline: v0.1.101/code102, release source a2bc3236b0ae9c033955c0f46e2131032d13c711.
Existing device PASS is REUSED_EVIDENCE, not new-version acceptance. #165 stays OPEN.

## Fragmented display ownership

`WindowVisualCapture` compared projection pixels with independently isolated app/IME
window captures. `verifiedRuns` discarded the whole image after 4096 rectangles,
even if a large static web-content corridor exactly matched. Animated web content
can produce this fragmentation; the recorded error alone does not prove which
particular pixels changed on Samsung Internet.

The new bounded fallback keeps only tiles whose **every pixel** matches the isolated
source. Tile geometry remains within one already certified visible surface. Unknown
pixels, region gaps and changed interior pixels are masked, never approximated.
The budget is divided across surfaces. Existing final RegionIterator cap remains.
No screenshot/gesture retry, tolerance, endpoint-only proof, permission change,
source-time relabeling or window-list-only ownership inference is introduced.
Exact masked image hash, full gesture corridor, IME exclusion, context, geometry,
session, display generation, editor privacy and revocation rechecks remain required.
If no safe corridor survives, capture/gesture remains BLOCKED.

Regression: checkerboard fragmentation in the left half of a 256x256 capture
exceeds the old limit while a right-hand scroll corridor remains fully provable.
A single unknown interior pixel rejects a whole coarse tile. Gaps stay unowned.

## Samsung IME

OS-selected IME owner, separate image/native key-label and literal-input effect paths
already exist for an authorized IME plus a focused, non-sensitive editable target.
`interaction_allowed=false` originates in runtime app access; it is not authority to
silently grant interaction. `input_target_verified=false` is separate: Termux's
noneditable terminal canvas is not a verified text editor.
New observation metadata exposes `interaction_reason`, distinguishing access policy
from missing input-target proof. `visual_key` now reports
`keyboard_interaction_not_authorized` before field admission when interaction is denied.
Existing policy gates remain; no runtime setting is changed by this Work.
Samsung letters/Hangul/deletion require authorized IME + real editor + fresh image
and native candidate + exact effect/caret verification. Ctrl+C is unsupported by the
visual-key contract; Enter/send/search remain excluded from visual_key. Do not invent
an automatic layout/modifier sequence or retry an UNKNOWN action.

## Termux

Binding availability is not safe-input availability. Observations now disclose
`terminal_selection_state=unknown`, `terminal_input_allowed=false` and the reason
while retaining `terminal_input_supported` as the historical binding capability.

The official Termux source confirms a separate TextSelectionCursorController and
`isSelectingText()`. InputConnection's `sendTextToTerminal` calls
`stopTextSelectionMode()` before sending characters. Its empty IME buffer therefore
cannot prove that native commit will preserve an existing screen selection.
Source: https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java
This upstream source is contract context, not an attestation of the installed
Google Play variant. The installed -1/-1 evidence remains UNKNOWN.
No trustworthy exported selection-absence contract for that installed variant has
been established. Normal/force/submit remain `terminal_selection_unverified`, with
zero dispatch. A cooperating verified terminal contract is needed to enable them;
absence of handles/menu, `isSelected=false`, empty surrounding text, or -1/-1 are
not sufficient. No existing selection is dismissed. Ctrl+C remains BLOCKED too.

## Separate physical acceptance (not performed in this Work)

| Item | State before installation | Required observation |
|---|---|---|
| Samsung Internet fragmented capture and vertical visual scroll | NOT_TESTED | new image + fully owned app corridor + independent actual scroll effect |
| Visual drag / cursor and text selection | NOT_TESTED | safe target/path + independent effect and preserved draft |
| Samsung IME visual characters | BLOCKED until authorized editor context | fresh owned key image, literal candidate, exact text/caret effect |
| Termux normal / force / submit / Ctrl+C | BLOCKED | trustworthy terminal screen-selection state contract first |
| Explicitly approved clipboard Paste | NOT_TESTED | readable provenance, separate replacement consent, exact effect |
| Sharing revocation | NOT_TESTED | image access denied immediately after actual OS revocation |
| Session replacement / UNKNOWN no-replay | NOT_TESTED | new-version physical acceptance; retain existing automatic guards |

#211 Self-Monitoring/Healing, #209 stop-sharing UI, #210 end-cause diagnostics and
#203 performance optimization are excluded. No phone install/control or config
mutation is performed here. This patch does not claim all P1 errors are solved.
