# Gemini question/reply round trip — v0.1.49 / code 50

Delivery record: 2026-10-18 KST. Device acceptance is **PENDING**.

## Implementation and evidence

- [PR #94](https://github.com/al-hub/closepaw/pull/94), merged source
  `74b03398042a3bbe419032525ff636f9ca9e27db`.
- Three agents implemented guarded submission and the Android UI adapter, reply
  correlation/collection, and flow/failure tests. The primary agent integrated the
  registry, local permission, task persistence, diagnostics and release.
- [PR Security CI](https://github.com/al-hub/closepaw/actions/runs/39026846779),
  [branch CI](https://github.com/al-hub/closepaw/actions/runs/39026844221), and
  [Android tunnel runtime](https://github.com/al-hub/closepaw/actions/runs/39026846870)
  passed. Downloaded XML reports contain **2,344 tests, zero failures/errors/skips**.
  This includes 58 new focused tests. Both bridge suites passed: 55 + 8 tests.
  The debug APK built successfully.
- Local Android tests could not start because the Gradle distribution was uncached
  and its download was blocked. CI is the Android execution evidence.
- Initial CI found a test-fixture callback inferred as returning `Any` instead of
  `Unit`; it was fixed before the successful runs. Review also added coverage for
  nested reply roles and all text truncation paths.
- Main CI: [39077483328](https://github.com/al-hub/closepaw/actions/runs/39077483328).
  Passed. [Signed Release 39077879279](https://github.com/al-hub/closepaw/actions/runs/39077879279) also passed.
- Published [v0.1.49](https://github.com/al-hub/closepaw/releases/tag/v0.1.49),
  [APK](https://github.com/al-hub/closepaw/releases/download/v0.1.49/closepaw-aa-bridge.apk) and checksum.
  Downloaded APK: **116,330,053 bytes**; SHA-256
  `939a82d3d938fe7188c2ddf07bb01876920d8bcbf599496cf8422bf45b481342`.
  The published checksum matches the actual file. Binary manifest inspection confirms
  `ai.closepaw`, version name `0.1.49`, code `50`. Both native runtimes are present.
- CI apksigner verification passed. Independent APK signing-block certificate
  SHA-256 matches the preserved signer:
  `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`.
  Annotated tag `v0.1.49` resolves to merged source `74b03398042a3bbe419032525ff636f9ca9e27db`.

## Contract and limits

`gemini.question.ask` is discovered through the existing MCP operation envelope.
It requires a separate, default-off local question-send permission. Draft-only
permission still cannot send. Pairing, tunnel settings and other permissions are
preserved, with no connector re-registration.

Only the current conversation is supported. Existing drafts, ambiguous targets,
ordinary Google Search, incomplete trees and permission/lock failures block writes.
The task ledger records the submission boundary before the semantic Send click.
Clicking and observing an actually submitted question are different states.
Duplicate request IDs, restart uncertainty and timeouts never automatically resend.

Collection requires a new reply correlated with the exact question plus completion
evidence and stability. Menus, prior replies and echoed questions are excluded.
Text/traversal limits produce partial results. Automatic scrolling and new-chat
selection are unsupported. Stopping observation does not cancel Gemini generation.
Diagnostics contain only bounded task/stage/reason/timing metadata; question
and answer content is not diagnostic log data. Bounded answers are private task
results returned through `get_task` output paging.

The adapter supports explicit semantic accessibility roles. Their presence on the
actual Galaxy/Gemini build must be tested; synthetic tests do not prove device
compatibility. Unsupported layouts fail closed.

## Device acceptance after signed APK installation

1. Update over the current app without uninstalling, resetting data or re-pairing.
   Confirm Settings reports **0.1.49 (50)** and the connection resumes.
2. Settings → ChatGPT Voice → **Allow ChatGPT Gemini questions**: enable once.
   Keep the phone unlocked and interactive during accessibility operations.
3. The previous draft-insertion test left an unsent draft. The user should clear it
   manually before the new round trip; do not bypass the draft-preservation guard.
4. Discover `gemini.question.ask`, then perform one explicitly requested question
   using one `request_id`. Poll that same task; never resend an uncertain result.
5. Verify one actual question submission, its new answer and completion evidence,
   followed by a grounded explanation in the same ChatGPT Voice conversation.
6. The agreed representative request is “Gemini를 통해 현재 위치 알려줘.” Distinguish
   the control round trip from location accuracy. A correctly collected permission
   or unknown-location response may pass control but does not prove location.
   Use a short general question only to isolate a location-specific limitation.
7. Record task ID, stage, timing, outcome class and user acceptance. Do not publish
   exact addresses, coordinates, question/answer text or private connection details.

P2 and real-device round-trip completion remain open until this evidence exists.
