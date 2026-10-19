# Owned popup observation — v0.1.64 candidate

Status: development. No v0.1.64 delivery or model-selection acceptance claimed.

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
