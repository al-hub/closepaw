# Native short Gemini reply collection — candidate v0.1.83/code84

## Evidence and diagnosis

Code83 task `8ef8ab53-ba0b-4edf-8163-3b1a3323747e` failed with
reply_timeout, sent=true, collection_ms45239. Do not resend it or relabel it PASS.
Its last metadata (observed_at_ms1792842200646, task completed1792842201045)
contains a visible native response text node of length2, a non-scrollable
assistant_robin_chat_history_list, and a separate immediately following native
actions-section row with visible copy/positive/negative targets. Metadata reports
reply_count0/reply_page_available=false. The later independent screen read observed
the expected numeric answer. The metadata contains lengths, not answer content;
first answer appearance and completion within the deadline remain unmeasured.

Two compatibility gaps explain why this structure cannot be collected by code83:
- Page selection requires vertical scroll actions even for a short native history.
- Native completion accepts toolbar descendants or a shared body/toolbar row, but
  this device attaches the toolbar in the immediately following history item.
The generic fallback also rejects unknown actionable content/status text in the
response interval. Rather than weaken that fallback, support the exact native
history/body/action ownership contract. This is a structural reproduction, not a
claim to have reconstructed a full raw accessibility snapshot from truncated metadata.

## Candidate change and safety

GeminiReplyPage.kt tries one safe known native history before generic scroller
selection. GeminiNativeReplyPageParser.kt additionally accepts an immediately
following history row with the known actions section and no question/body owner.
Only the known actions subtree contributes completion controls. Require unique
exact question/response ownership and three distinct visible enabled actionable
copy/positive/negative targets. Non-scrollable roots never authorize scrolling.
Existing package/ancestor/traversal checks, stable observation, generation/cancel/
lock/session guards and prior-text exclusion remain. Generic parsing is unchanged.

GeminiNativeShortReplyTest covers the sanitized layout, body-only output, no
invented scroll capability, missing/hidden/disabled completion roles, foreign toolbar,
later turn/multiple bodies, unknown history, foreign package/hidden/truncated tree,
and parser-to-collector stability/prior-text exclusion with no write/send/scroll.

## Verification state

- Local diff whitespace check PASS.
- Local Gradle NOT_TESTED: wrapper distribution unavailable (Network is unreachable).
  This is not a failing assertion or a demonstrated RED run.
- Exact-source PR/main CI, signed/public APK and installed candidate NOT_TESTED until
  separately recorded. Existing v0.1.82 release/install and physical X/unlock/rotation/
  island evidence are REUSED_EVIDENCE with their original source/version.
- #145 actual expanded detail-panel rendering remains NOT_TESTED; #147 Android/Voice
  speed improvement remains unproven. #146/#150 stay closed.
- No new phone session/question/install occurred during this analysis. Do not resend
  the old question. Only a newly authorized non-repeating acceptance case may follow
  a verified candidate installation; do not rerun the historical physical tests.

## Clock domains

Task *_at_ms fields are wall-clock timestamps; no duration is derived across clock
domains. Collection duration is injected clockMillis, default System.nanoTime/1000000
in GeminiReplyCollector.kt and passed into GeminiPagedReplyCollector. Diagnostics
persistence timestamp_ms uses System.currentTimeMillis. No Voice/network latency,
first-visible-answer time or performance gain is inferred from these values.

## Release verification (2026-10-24)

PR [#156](https://github.com/al-hub/closepaw/pull/156), exact head
45c5609a7d2a752ebb26d970c5a375607dcdfd5f: Security40096550298 and UI40096550287
success. Independent downloaded XML: JVM2743 (new class6), UI10; failures/errors/
skips0. Four light/dark/font renders inspected. PR unsigned-release intentionally
skipped; main optimized release retained.

Merged immutable APK/tag source430f42290d2f8bdc3d10c0c451d677ae7f6f6407.
Main Security40097087564, UI40097087636 and Signed40097583090 success. Main XML
independently parsed2743 with failures/errors/skips0. Public verification artifact
12561855215 ties certificate/manifest/hash to the same source.

[Release v0.1.83](https://github.com/al-hub/closepaw/releases/tag/v0.1.83):
116642415 bytes; SHA256
c55a6b402d6f04513f72b73bcf77cd38ae1356cc3f1fa0925a26c0e084065b15.
Anonymous APK+checksum downloaded independently. Existing operating signer
SHA256d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba,
v3 RSA-SHA512 signed-data signature and full chunked APK content digest,
binary manifest ai.closepaw/0.1.83/84, arm64 openaitunnel+cloudflared and annotated
tag99c3a68f904a0715cd0c77a2d1a57669c75af95b→source430f422... all independently PASS.

Raw artifact ZIP SHA256 checks:
- PR UI12561386762: e4bf9136c11d03f5748f2e657232db00c44bfdcbabc522241b98dd0fb3697334
- PR XML12561346181: 754fc3fefc9728b0e1559d5151105c9e63beb4f5a59bec4f0449fe2027f3f648
- main XML12561133926: 35303061554d2f220c248a7ea182304e2462a4cda62a556f9a11e308f62e2ba6
- public verification12561855215: 154bf99ac5d531d4678adad8ef8e9640c4c5c895c8a156ee896c6363a75f57e9

Installation was submitted exactly once: task78bb9aa8-daec-45a7-a259-bc87482f85ba.
The first post-request status query timed out during replacement; no install replay.
After a delay, durable self_update.status confirmed installed/current0.1.83/code84,
then independent get_status confirmed reachable0.1.83/code84. Installation PASS.

## Single candidate device acceptance — FAIL, no replay

New authorized session85c9019e-13d5-4eab-a04e-1492b96032b1/epoch1,
start task62ab6c91-e57a-4b85-a358-65b31ebe8e8a. One NEW harmless arithmetic
question, task9cf42b92-ae35-4c7b-bbdc-9040e550ad83; not the previous question.
Terminal outcome_unknown/target_changed, send_action_invoked=true, sent=false,
collection_ms80. Last metadata foreground=false/target_not_verified, same observed
Google package and window2349; metadata suppresses node data while unverified.
This does not prove app switching, user interference, missing composer specifically,
or a regression introduced by the native parser change.

Read-only session.status remained active. One subsequent read_app text+bounded JPEG
showed the new synthetic question and expected numeric answer with toolbar. This
supports later visible delivery but does not rewrite the original unknown task as
PASS or establish when the answer first appeared. No resend/no ui.inspect retry.
The earlier foreground/identity guard stopped before native reply recognition could
be accepted on device. Static regression PASS does not prove the physical fix.

Diagnostic elapsed_ms (Android SystemClock.elapsedRealtime, invocation-local start): not_sent1813,
submitting4369, send_action_invoked4844, waiting5180, outcome_unknown5554.
collection_ms80 uses the collector's separate System.nanoTime-derived clock/start;
no Voice/transport components or speedup are inferred. Diagnostic timestamp_ms is
wall time and is not subtracted from monotonic values.

End task57a506c3-7273-42fd-bf69-1d3bad235304 succeeded; independent status confirmed
ended/epoch2. Existing X/unlock/rotation/drag tests were not repeated. Screenshot
showed only collapsed island/X, so expanded progress rendering remains NOT_TESTED.

Next: distinguish missing root, temporarily missing/ambiguous composer and changed
window using privacy-safe observation evidence. Design/test a bounded READ-ONLY
same-window settling path only if justified; keep wrong app/window, lock, revocation,
user intervention, cancellation and stale data fail-closed. Never recover by sending
again or marking unknown outcomes successful. No further device question is authorized
by this completed acceptance attempt. #145 and #147 remain OPEN; #146/#150 CLOSED.
