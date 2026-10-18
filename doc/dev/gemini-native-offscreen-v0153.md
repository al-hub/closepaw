# Native Gemini offscreen response ownership — v0.1.53 / code 54

## Device evidence (2026-10-18)

Installed v0.1.52/code53 was confirmed. Task `04efdc30-814e-45ac-b5ae-acc0d346da47`
confirmed one submission, then timed out after 119,827 ms with `pages_collected=0`, no
returned answer and unconfirmed coverage. Its latest page had the exact question visible,
forward/backward scrolling available and no visible completion footer. No resend occurred.

A subsequent bounded metadata inspection showed the old response (2,744 characters) and
its feedback/copy toolbar visible, the current user message visible, and a different native
response (2,598 characters) present but offscreen (`visible=false`). A screen read therefore
exposed the old body, not the new answer. This must never be reported as successful collection.
The native sibling order was old content_message_container → current user_message_text →
new content_message_container, under assistant_robin_chat_history_list.

## Narrow correction

- A native-only parser uses the exact current user-message ID/text and structurally subsequent
  native response owner under the verified foreground history list. It reads offscreen native
  assistant_robin_text descendants only from that response, allowing the collector to start
  forward scrolling. It never reads a background app or bypasses lock/consent requirements.
- Copy/feedback completion requires visible, distinct actionable controls inside that same
  response owner. The older response toolbar and offscreen current toolbar cannot complete it.
- Disabled, password, editable/composer, foreign-package, menu/dialog and invalid ancestry
  nodes remain excluded. Ambiguous owners or later user turns stop collection. Baseline text
  fingerprints, stable body, overlap/character/time/action limits remain in force.
- Generic layouts retain the previous parser. Clipped screen geometry cannot override native
  response ownership. Metadata contains no question or answer text.
- Regressions reproduce the observed two-response tree and actual Android adapter send→hidden
  body capture→forward scroll→current visible completion controls, with exactly one send.

CI, signed release and installed-device acceptance are pending. Neither v0.1.51 nor v0.1.52
passed automatic long-answer acceptance; their successful CI/signed delivery does not close it.
