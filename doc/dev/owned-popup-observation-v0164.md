# Owned popup observation — v0.1.64 candidate

Status: v0.1.64/code65 DELIVERED and INSTALLED. Actual OS-linked popup observation, requested3.8 Flash selection and one subsequent dedicated question/new-reply collection ACCEPTED. Public download/signature/update/reconnection/Termux/latest-version checks passed. Earlier development checkpoints below are historical. P2/Voice and matched end-to-end latency remain open.

## Actual evidence (2026-10-19 21:28 KST)

On installed v0.1.63/code64, generic Gemini list/exact launch succeeded (1,635ms).
Fresh complete104-node/depth33 observation exposed the current clickable model-title parent
and node_tap_available=true. Exactly one explicit node tap completed (942ms) but reported
screen_changed=false/acknowledged_no_visible_change. A later fresh accessibility observation
still exposed only the original104-node composer tree and no model options.

The supported bounded visual read then directly showed the model picker OPEN, with the user's
requested3.8 Flash option visible and the prior model still selected. It reported the app already
foreground and took one screenshot, while its accessibility content also omitted the popup.
Thus the picker is visibly open after the single tap, while the active-root-only observation misses it. The immediately preceding menu-closed state was not independently imaged; gesture completion and post-tap visibility do not by themselves prove that prior state.
The earlier no-menu conclusion from accessibility alone is corrected. No tap was repeated,
no model was selected and no question was sent. No private screenshot/account text is published.

## Narrow correction

Enumerate current interactive windows rather than assuming rootInActiveWindow is the only
action surface. Accept additional application roots only when the OS parent/anchor relationship
links them to the same verified foreground application, on the same display and above it.
Same-package co-occurrence is insufficient. Fresh secure-window probes must cover every
included window. Scan all included trees for identity/protected input and fingerprint them;
expose/action only the topmost owned surface, with unique IDs and one total traversal budget.
Revalidate foreground ownership, window set and geometry at the native boundary. Preserve
all consent, lock, blocked-app, protected-input, unknown-send, single-use and chain guards.
Bounded text-free window diagnostics must make missing roots/unlinked popups explicit.

Use RED-first selection regression, then full unit/lint/debug CI and the existing main/release
pipeline. The actual signed release provides R8/signing validation; a duplicate PR spike build
is unnecessary for this observer correction. Public download/hash/signer/source verification,
one update and actual popup/model/question acceptance follow. Existing v0.1.63 delivery and
maintenance results are retained. Model names remain outside the executor.

Primary API contracts:
- https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo
- https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/services/core/java/com/android/server/wm/WindowState.java

RED: Security CI39252774025/job118060417935 compiled2,562 tests with exactly1 expected assertion
failure: aNonFocusedOwnedPopupMustReplaceTheUnderlyingActionSurface (expected popup11,
actual underlying host10). No compilation failure. Test-only head6ae53a2e5d8816e7720a455f2a359f7ba14dabeb.

Implementation includes up to4 OS-linked application roots within the existing single512-node/
depth64/output50-node budget. It handles a nonfocused popup above a focused host AND a focused
popup with an unfocused linked host. All included roots are freshly screenshot-security-probed,
scanned/fingerprinted; only the topmost root exposes action nodes. Focus-owner/window-set changes
invalidate observations. Missing/unlinked/foreign/ambiguous roots fail closed with bounded metadata
(no titles, package names, account identifiers or screen text). Checkable/checked states are exposed.

GREEN: Security CI39253738739/job118063694996 succeeded on e20219f8d5f3f5e9374860ae2427a8c847bb41c9.
Independently parsed report artifact12186556851:270 suites/2,571 tests/0 failures/errors/skips;
lint0 errors/108 warnings/4 informational. Python Termux55/ChatGPT bridge8 and native/debug APK passed.
PR #109 merged as6703a873296a51c32985198e8dd63b6d8db9ff96. Main CI and signed release/public
verification follow; these build results are not actual menu/model/question acceptance.

## Signed public delivery

Main Security CI39254537754 and Signed Release39255478075/job118069565790 succeeded.
Immutable source/tag:6703a873296a51c32985198e8dd63b6d8db9ff96/v0.1.64 (annotated tag dereferenced
and matched). Public APK:https://github.com/al-hub/closepaw/releases/download/v0.1.64/closepaw-aa-bridge.apk
Size116,443,909 bytes; SHA-25674345d0fb264dda6380443891f5247e2572163f53de025562fdbff0ab0da2d9f.
Independent anonymous public download matched the built APK/checksum, original signer
(d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba), manifest ai.closepaw/0.1.64/
code65, both native runtimes and immutable source/tag. GitHub public asset digest also matches.

Latest device check returned available0.1.64. Pre-install screen observation returned
secure_window_changed, not device_locked; do not claim a known-locked install from that sample.
Exactly one remote update request targeted0.1.64, progressing to installing/current0.1.63/code64.
Installing is not accepted as installed; reconnect/current-version verification follows.

## Actual installed-device acceptance (2026-10-19 KST)

Exactly one remote update request confirmed installed_version=current_version=0.1.64/current_code65
on the existing connection, with install-source and remote-update permissions retained. No repeated
install, pairing or plugin registration. Operation IDs/input schema hashes remain compatible;
rediscovery exposes the popup-aware description and output diagnostics.

The actual screen was unlocked. A fresh generic Gemini label lookup selected its exact launch
package. The launch acknowledgement initially had an uncertain post-observation during a window
transition; no launch was replayed for that uncertainty. A fresh observation then SUCCEEDED:
foreground host1885, topmost popup1889, owned_window_count2, actual OS parent_id1885 on the popup,
application=true/same_package=true, popup active=false/focused=false, host active=true/focused=true.
Complete129-node/depth33 traversal, no truncation; only popup action nodes exposed. The actual
requested3.8 Flash label and its clickable parent were returned. This directly accepts the observer
fix on the real nonfocused popup, beyond fixtures/build success.

One native click selected that observed3.8 Flash parent. The immediate post-probe raced popup
removal (secure_window_probe_failed/outcome_unknown); the click was not blindly replayed.
A fresh observation showed the popup gone and title Gemini Flash. One explicit observed-title tap
reopened the picker for verification; a later fresh observation again found its actual OS-linked
popup. All selected/checked accessibility flags were false even on the selected row, so those flags
were NOT treated as proof. The already-foreground bounded image positively showed the checkmark
beside3.8 Flash and the title Gemini Flash. Requested model selection is therefore ACCEPTED using
fresh native title plus actual visual checkmark, not an acknowledgement or inferred family label.
No private image/account content is published.

Back first was followed by IME dismissal while the nonfocused popup remained; a subsequent fresh
Back was followed by ChatGPT foreground. Concurrent user/app foreground changes were not separately
instrumented, so this is not treated as a proven popup-dismiss action. On relaunch, the verification menu was still present. No further blind Back
loop was used. The already-confirmed selected3.8 Flash row was deliberately clicked to dismiss
that verification menu, preserving the selected value. A fresh observation confirmed one owned
host, title Gemini Flash and an empty editable composer before the dedicated question path.
This was a known-state menu dismissal after positive selection proof, not replay of an unresolved
model write.

Exactly ONE benign synthetic acceptance question was sent via gemini.question.ask in the current
conversation (task8a758681-5a16-4c79-82ad-3d96f44a0b0a). Confirmed result: succeeded/completed,
reason_code=reply_completed, submission_state=sent, sent=true, send_action_invoked=true,
outcome_unknown=false, partial=false, timed_out=false, pages_collected1, coverage=complete,
completion_evidence=semantic_complete_and_stable. The returned one-character numeric answer
matched the synthetic arithmetic check. No user question/reply or screen contents are published.
Collector timing: collection_ms3,725/completion_observed_ms3,121. These are one device sample,
not a matched before/after Voice or end-to-end benchmark. Post-send ui_metadata may say
send_control_missing because the composer is empty; the authoritative task/result confirms
reply_completed, not a send failure.

A final generic observation succeeded on the105-node, untruncated host with title Gemini Flash
and the synthetic reply visible. Durable current version remained0.1.64/code65. One Termux id
task succeeded exit0 (102ms, no timeout/unknown result); latest release check returned up_to_date.
Known-locked install/maintenance coverage from v0.1.63 is retained; the v0.1.64 pre-install
sample was secure_window_changed and unlocked acceptance followed, so it is not relabelled
as a new known-locked test.

## Remaining limitations and follow-up

- Immediate Android window metadata can lag a launch/menu appearance/removal. Some action tasks
  conservatively returned outcome_unknown or acknowledged_no_visible_change; fresh read-only
  observations and the visual check resolved the actual requested result. Never replay a write
  merely because of those task states. Consider bounded post-observation settling without any
  mutation replay in future work; no such timing improvement is claimed here.
- Back did not provide a verified popup dismissal in this sample; IME/foreground changes were
  observed instead. Use actual post-observations; do not assume Back closed it or loop blindly.
- Android accessibility did not expose this menu's selection as selected/checked; actual image
  verification was required for its checkmark. The same-package-only exception remains forbidden.
- Generic menu/model selection plus dedicated question handoff are accepted for this observed
  device/UI/version. Existing Gemini/Chrome app discovery/launch and historical long-reply coverage
  are retained, not repeated unnecessarily. This does not establish control of every Android app,
  protected/authentication screens, or a Gemini non-screen/locked execution path.
- P2/Voice and matched speech/end-to-end latency remain open; do not close the entire Voice project
  from this narrower controller acceptance. No private screenshots, account text or shell output
  belong in this public repository.
