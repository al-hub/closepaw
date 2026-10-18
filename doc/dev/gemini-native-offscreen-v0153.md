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

## Verified delivery

- PR #98 merged as `feecbe5c23a2dd545c3d3c9568d6b1f871489bca`.
- Android XML reports: **2,472 tests; zero failures, errors or skips**. PR Security CI
  `39104502533`, main Security CI `39104833661`, signed native runtime probe
  `39104502485`, and Signed Release `39105170441` all succeeded.
- [v0.1.53/code54 APK](https://github.com/al-hub/closepaw/releases/download/v0.1.53/closepaw-aa-bridge.apk)
  independently downloaded: 116,378,621 bytes. Manifest version/package, checksum file,
  GitHub asset digest, APK v2 signature, previous signing identity, AArch64 native runtimes
  and immutable release tag target verified.
- APK SHA-256: `255e2f654b7ac981b6b76a5dd23608ae5d1cb1304449b06134463975e0303b81`.
- Signing certificate SHA-256:
  `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`.
- Bundled native hashes remain unchanged: OpenAI tunnel
  `d25d4f8977ec93089dfed41667acd91ab6174042e8c6943428a150dca8895288`,
  cloudflared `5a16aea0f429f1dfc635b7d61c21cd1c357f4d0e7b8992d16cadf160f7166f76`.

**Installed v0.1.53 acceptance is pending; P2 remains ACTIVE.** Neither v0.1.51 nor v0.1.52
passed automatic long-answer acceptance; successful CI/signed delivery does not close it.
The v0.1.51 short test's completion-evidence→collection interval was 585 ms, which does
not establish visual-completion→speech latency. Next acceptance needs a fresh unique long
prompt, first/last item and coverage verification, and foreground/unlocked Gemini throughout.
