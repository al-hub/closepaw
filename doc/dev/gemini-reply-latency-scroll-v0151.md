# Gemini reply latency and bounded full-answer collection — v0.1.51 / code 52

## Verified signed delivery — 2026-10-18

- [PR #96](https://github.com/al-hub/closepaw/pull/96) merged at `1232e6aeba7523e6013d0bfd1c5f783103464dc5`.
- Final PR Android tests: **2,457 passed, 0 failed/errors/skipped**, including actual Android adapter
  send→scroll→reply collection. Main Security CI and the signed runtime probe also passed.
- [Signed Release run 39101240835](https://github.com/al-hub/closepaw/actions/runs/39101240835) succeeded.
- [Published v0.1.51 APK](https://github.com/al-hub/closepaw/releases/tag/v0.1.51): package `ai.closepaw`, version `0.1.51`, code `52`,
  **116,370,657 bytes**. Downloaded the public APK and checksum; `aapt` manifest and
  `apksigner verify` passed (APK v2 signature).
- APK SHA-256: `487fda71c6f01c13e9315d324eca6921109cdb8faec05118910218d1a6295fd6`. Matches both the published checksum file and GitHub asset digest.
- Signing certificate SHA-256: `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`. Matches the previously
  verified v0.1.50 certificate; CI also compared against the original v0.1.33 release.
- Both arm64 native tunnel runtimes are present and ELF AArch64. Annotated version tag resolves
  to the exact validated main commit above.
- **Device acceptance remains pending:** short-answer perceived latency and long-answer paging
  have not yet been verified on the installed v0.1.51. Keep Gemini unlocked and foreground.
  CI/signed delivery is complete; do not claim all Gemini layouts or full Voice latency are verified.


## Observed user feedback (2026-10-18)

v0.1.50 basic text and Voice round trips succeeded on the Galaxy. During three ordinary requests,
one reply was collected, one collection stopped after foreground changed, and another needed a later
screen read after delayed collection. These are usage observations, not a three-of-three acceptance pass.
The user elected to keep using the app and fix recurring issues. The current request addresses both:
(1) noticeable delay after Gemini visually finishes; (2) unknown coverage below the visible viewport.
No question bodies, answers, location values or other personal content belong in public diagnostics.

## Implementation

- Short-answer observation polls every 200 ms and requires 400 ms body stability with existing
  semantic completion evidence. A pause in text alone never proves completion.
- Gemini `get_task` holds a running read for up to 8 seconds, returning when the durable task becomes
  terminal. Terminal reads, output pagination and other operations return immediately. MCP tool schemas
  remain unchanged. The wait never submits or retries a question.
- A unique, non-composer conversation scroll region is revalidated on every action. Native
  ACTION_SCROLL_FORWARD/BACKWARD are the only new UI actions; no coordinate clicks, clipboard or
  generated command fallback. Android action documentation:
  https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction
- If initially at the response tail, paging seeks the exact submitted question, then captures the body
  forward in order. Consecutive pages must have exact suffix/prefix overlap. Missing overlap, unverified
  boundaries, new user turns, lock, consent revocation, foreground changes, no-op scrolling, traversal,
  time, character and action limits cannot report full success.
- Maximum 24 scroll actions, 16,000 answer characters, original request timeout (up to 120 seconds).
  A verified response footer plus stable body is required; a successful scroll action is not end proof.
- `collection_ms`, `completion_observed_ms` (short-answer path), `pages_collected` and `coverage`
  provide content-free timing/coverage evidence. Task stdout pagination is still required when has_more.

## Limits / device acceptance

Gemini must stay unlocked and foreground. Unknown/nested/carousel scroll structures and unsupported
interactive answer layouts deliberately fail closed or return partial output. Some Gemini versions may
not expose an independent accessible conversation scroll container. Do not call those answers complete.
The implementation reduces observation and task-retrieval delay; it cannot promise immediate speech
because MCP transport and ChatGPT voice processing remain outside the app.

After installing the signed APK, test a short answer and a long numbered answer spanning 3+ viewports.
Record first semantic completion, task completion, pages/coverage and user-perceived speech delay.
Check the first and last numbered items, repeated paragraphs, no duplicate send, foreground interruption
and the partial-output reason. Passing CI and a signed APK do not substitute for these device checks.
