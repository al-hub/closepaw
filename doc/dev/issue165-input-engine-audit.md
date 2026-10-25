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
