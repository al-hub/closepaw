# Gemini hosted screen read — v0.1.48 / code 49 released

## Reproduced device failure

On v0.1.47, request `f17acf1b-bc7a-4b8c-b57a-160b9bf747fb` returned
`target_not_foreground`, observed package `com.google.android.googlequicksearchbox`.
No text or screenshot was captured. The earlier System UI failure is separate;
after the user unlocked, the host-package rejection remained.

The read path required exact `com.google.android.apps.bard` equality, unlike the
existing draft adapter which positively identifies Google's Gemini host by a visible,
enabled, non-password, editable Gemini-specific composer. The observed package alone
does not establish that the screen was Gemini rather than Google Search.

## Change and boundaries

- Reuse the existing read-only composer inspection for hosted Gemini identity.
  Do not add Google Search as a read target or allow an arbitrary Google search field.
- Keep native Gemini, Samsung Internet and Chrome target contracts unchanged.
- Bound asynchronous launch handoff checks. Read text from the verified package/window;
  recheck identity before and after the single screenshot. Discard content on change.
- This path never sets composer text, clicks Send or creates a Gemini response.
- Operation versions, schema hashes, MCP definitions, pairing, tunnel and data persist.
- The helper temporarily keeps its existing draft-adapter name; reads only invoke
  isLocked/inspect/evidence and never launch/setIfEmpty/matches on that adapter.

## Validation status

- TDD red confirmed: [Security CI 39022451710](https://github.com/al-hub/closepaw/actions/runs/39022451710),
  commit `59233e7f`: 2,283 tests, exactly one failure,
  `ReadAppForegroundPolicyTest.acceptsGoogleHostedGeminiOnlyAfterComposerIdentityVerification`.
  The old exact-package condition rejects the positively verified host.
- Policy tests reject generic Search, unrelated apps, null foreground and host use for
  Chrome/Samsung. Actual-read integration tests check hosted content, one capture,
  no draft writes, Search refusal and identity loss during capture.
- Local Gradle cannot download its distribution because network is unreachable.
  GitHub Security CI and Signed Release remain authoritative; record actual results.
- PR #93 merged as `209dc9e20767cdfd39ca59ff2c7f1936de539579`.
- [Green PR Security CI](https://github.com/al-hub/closepaw/actions/runs/39022708800),
  [main CI](https://github.com/al-hub/closepaw/actions/runs/39023231120), and
  [Signed Release](https://github.com/al-hub/closepaw/actions/runs/39023499964) passed.
- Published v0.1.48/code49 APK and checksum; source tag version verified, same-signer
  validation passed. APK SHA-256 `9045eaa1afeeb065c3dc95310295ec523168dc8af59f31e74a0eefc04ed24bef`, 116,306,905 bytes.
- Device installation and actual Gemini READ/Voice/draft acceptance remain PENDING.

## Device acceptance after signed publication

Install over the current app without data reset or re-pairing. Verify version/code
and discover the current operation schemas. On an unlocked phone, run one Gemini READ.
If identity cannot be verified, stop and use privacy-safe metadata; do not broaden the
allowlist or retry indefinitely. A composer/model menu is not a generated answer.
Summarize only captured visible response text in the same Voice conversation.
Draft-input acceptance, permission/lock guards and ninth-tool client discovery are
separate gates. P2 remains ACTIVE until actual Gemini READ/Voice acceptance.
