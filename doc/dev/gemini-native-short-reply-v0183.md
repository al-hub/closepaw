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
