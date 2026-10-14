# Gemini draft operation: v0.1.42

## Goal and baseline

Keep the v0.1.41 connection, all eight tools/list definitions, Secure Tunnel configuration,
private MCP endpoint and Termux pairing token. Add `gemini.draft.insert` through the
operation registry only. The operation count increases from three to four. No MCP
plugin recreation, schema refresh or Termux bridge deployment is part of this update.

The input schema is `{ "text": "nonempty draft, at most 8000 UTF-16 characters" }`.
Use discover_operations for the current version/hash; execute with a unique request_id,
then get_task for the confirmed result. The operation is deliberately not a query.

## Implementation and limits

- Dedicated local **Allow ChatGPT Gemini draft input** switch, default off, separate
  from **Allow ChatGPT Termux execution**. No stored tokens/settings are reset.
- Native Gemini only (`com.google.android.apps.bard`), Accessibility service required.
- Require an unlocked, interactive phone. Launch Gemini, find exactly one visible,
  enabled, non-password editable composer by known prompt label/resource ID.
- Recheck foreground, local permission, node freshness and empty draft before
  ACTION_SET_TEXT. Never click Send, inject Enter, use the clipboard or tap coordinates.
- Existing drafts, ambiguous/missing composers and wrong foreground fail closed.
- Compare the composer text to the requested Unicode text; success requires equality.
  Unverified writes report outcome_unknown and must not be automatically replayed.
- Serialized with operation screen reads. Legacy read_app can still change foreground;
  the write and verification recheck the target. Manual user interaction can race
  Accessibility operations; do not interact with Gemini during the brief test.
- Native Gemini UI varies by version. Unsupported composers return composer_not_found;
  real Galaxy acceptance remains required. No claim of lock-screen UI control.

## Diagnostics

Metadata only: task_id (maps to execute request_id), stage, reason_code, elapsed_ms,
and timestamp_ms. Stages: start, launch, find_composer, set_text, verify, complete or
interrupted. No question text, UI text, tokens or exception messages are logged.

Each get_task result includes bounded per-task diagnostics. The existing get_diagnostics
response adds `gemini_draft.events`; its input schema and tool definition stay unchanged.
The last 64 events persist in app-private `mcp/gemini-draft-diagnostics.json` and Logcat
uses tag `ClosePawGeminiDraft`. Service restart can mark unfinished tasks outcome_unknown;
use the persisted diagnostics to investigate rather than replaying the input.

## Validation

Unit tests cover lock/permission failure, existing drafts, composer ambiguity,
foreground changes, unverified writes, Unicode preservation, diagnostic bounds and
recreation, unchanged eight tool definitions, actual operation discovery, duplicate
request suppression, and operation-specific permission isolation. Android compilation
and the full JVM suite run in Security CI; local Gradle download is unavailable.

## Real-device acceptance after signed update

1. Before update: record version 0.1.41, tool count 8, operation count 3 and an existing
   KV task ID. Keep the existing plugin and tunnel. Do not expose actual tokens.
2. Install signed v0.1.42 over the current app. Do not uninstall or clear data.
3. Reopen ClosePaw and Connect if the service stopped during package update. This is
   restarting the saved connection, not re-registering the plugin.
4. Enable **Allow ChatGPT Gemini draft input** in the existing ChatGPT Voice settings.
5. In the SAME ChatGPT connection, get_status should report 0.1.42/code43.
   discover_operations should return four operations, including gemini.draft.insert;
   the eight tool definitions must be unchanged.
6. Without re-pairing, run a harmless Termux command and inspect its terminal result.
7. Unlock the phone. Ensure Gemini's composer is empty; request an innocuous draft
   such as `오늘 할 일을 정리해줘 😀`. Execute the discovered operation once.
   Confirm succeeded, verified=true, send_action_invoked=false, then read_app(gemini)
   and verify it is still a draft, not a submitted message.
8. With that draft still present, a new request must report existing_draft and preserve it.
   A duplicate request_id with identical input must retrieve the same task.
9. Test locked-screen refusal and local-switch refusal. Inspect metadata diagnostics.

APK publication proves build/signing/integrity, not the above device acceptance.
