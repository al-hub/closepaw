# #165 unified screen-input engine — source audit and implementation checkpoint

Status: PARTIAL IMPLEMENTATION ONLY. This document is not device acceptance evidence.

## Existing building blocks on main (2026-10-25)
- `tool/action/ClickExecutor`, `LongPressExecutor`, `ScrollExecutor`, `SwipeExecutor`, `TypeExecutor`: existing cross-app node actions and gesture fallback.
- `platform/AccessibilityGestureInjector`, `NodeActionPerformer`: existing Android gesture and accessibility text action.
- `chatgpt/control/GenericControlActionPolicy`: freshness/window/fingerprint policy on the ChatGPT control surface.
- `tool/impl/MobileActionTool`: existing click, long_press, scroll, swipe and type routing.
- Existing type path assumes `ACTION_SET_TEXT` returning success means the action executed; post-observation is supplemental, not an exact text-effect guarantee.
- #165 clipboard copy/Paste and visible-IME per-key targeting are **not** implemented by this checkpoint. Termux terminal node may not be editable.

## Initial source change
- `TextInputFallbackGate` restricts fallback to exact explicit pre-dispatch unsupported/missing-node failures.
- Any other failure is `outcome_unknown` and stops automatic repeat. No input data is included in the new failure message.
- `TypeExecutor` now applies this check before tap-to-focus retry. This conservative change may intentionally refuse previously permitted fallback and needs device regression.

## Remaining engineering gates — all unverified
1. Map source-specific control APIs and ensure common guard parity, including session epoch, foreground, permission, IME owner, fresh observation, secure flags and user X.
2. Use true observed node/window/IME geometry for dynamic tap/long-press/swipe/keyboard-key coordinates, with changed-layout fail-close.
3. Build opt-in clipboard writer and Paste only for allowed non-sensitive input, with OS-supported conditional restoration and no competing clipboard overwrite.
4. Add supported direct/clipboard/IME channel selection without unknown/partial-effect replay; distinguish Enter/submit/command approval.
5. Add full unit/error-injection, Android instrumentation, independent security review and CI; fix before merge.
6. Main merge, existing-key signing, public APK independent signature/hash verification, single coordinated phone installation and 2 apps + Termux acceptance.
7. Record exact source SHA, tested Android build, IME, metrics and raw evidence in #165. Keep issue OPEN until actual required results PASS.

## Parallel ownership
Do not touch #152 bridge recovery, #161 benchmarks/experimental branch, or active UI device sessions. No APK release for a partial unaccepted change.

## Security concerns noticed in existing sources (follow-up review required)
- `NodeActionPerformer.setTextOnNode` returns a failure message containing intended/actual text, which may expose sensitive input via logs or tool results. Sanitize at source before enabling new input channels.
- `MobileActionTool.buildDescription` previews input text. Redact input payload in instrumentation, actions and UI descriptions.
- Prioritize comprehensive security policy parity between `mobile_action` and `android.ui.action` before exposing new gesture/clipboard commands.


## Additional implementation checkpoint — input policy and real IME blocker

- `UnifiedTextInputPolicy` defines a pure evidence-gated priority order: direct API, clipboard Paste, visible IME. It does **not** currently call Android clipboard or inject keyboard taps. A successful test of this policy only validates branching, not real input.
- Explicit `DEFINITELY_UNSUPPORTED` permits moving to the next evidenced channel; UNKNOWN/PARTIAL/CANCELLED/VERIFIED never trigger replay. Stale frame, missing app policy, unverified target, sensitive field or ended session all block selection.
- `AccessibilityPlatform.collectRootsOnActiveDisplay()` currently **excludes TYPE_INPUT_METHOD windows** when building perceptual elements (although keyboard visibility is detected). Thus the current snapshot cannot establish individual IME key identity, bounds or owner. Keyboard taps must not be enabled until a separate guarded IME observation contract, ownership checks and rotation/layout-change tests are built.
- `AccessibilityPlatform.performAction(UIAction.TapAt)` exposes low-level gestures, but this does not establish that external ChatGPT surface has permission/freshness for any arbitrary IME coordinate. The shared action adapter must not bypass `GenericControlActionPolicy`, security and session controls.
- Clipboard writing and restoration remain OS-policy dependent and are **NOT_IMPLEMENTED**. No automatic clipboard fallback is enabled.
- Release, PR merge and close remain prohibited until implementation + CI + signed source + installed phone acceptance are proven.


## 2026-10-25 native Paste primitive checkpoint

- `NodeActionPerformer.performPasteOnFocused(authorized)` now invokes Android Accessibility `ACTION_PASTE` only for explicitly authorized, focused, editable, non-password nodes advertising that action. Single dispatch only; a false result is UNKNOWN and must never cause automatic replay. Added tests for unauthorized, supported, password and unsupported node cases.
- **Not exposed to remote tools.** The method does not read/write/restore clipboard contents, has not been connected to `UnifiedTextInputPolicy`, and does not establish full foreground/window/session consent by itself. An approved higher-level orchestrator must provide that security contract before calling this primitive.
- `Success` reports dispatch only and explicitly labels effect unverified; it is not an assertion that text was pasted.
- No phone or active UI session touched, no release or main merge authorized by this partial step.


## 2026-10-25 internal AccessibilityPlatform integration checkpoint

- `AccessibilityPlatform.stageAndPasteWithFreshGuards` now assembles existing guarded clipboard staging + native Paste. It enforces explicit operation and target predicates at the stage and paste boundaries, rechecks accessibility permission, foreground package and blocked windows, and is intentionally **internal / not remotely exposed**.
- This is **not end-to-end completion**. The consent/target predicates have no wired authoritative caller, the method has not been exercised on a physical device, clipboard cannot safely be restored on unknown interruption, and no exact post-paste text verification or dynamic IME observation is present. Keep #165 open and PR draft.
