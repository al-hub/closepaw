# Identical whole native body across scrolls — v0.1.54 / code55

## Installed v0.1.54/code55 — long-answer acceptance passed (2026-10-18 17:38 KST)

- Device status confirmed v0.1.54/code55. Task `e242dd43-78ac-424a-8033-cf53f4bce7c9`, request `gemini-v0154-long-20261018-1738-01`: `succeeded/reply_completed`, `sent=true`, `partial=false`, `coverage=complete`, `completion_evidence=paged_start_overlap_end_and_stable`. One submission, no resend.
- All40 requested items (301 through340) and every double/triple value were checked against the returned text; no missing/duplicate item or truncated output. Final foreground page had a visible completion footer and no forward action remaining.
- Collector duration **14,876ms**, accepted task→terminal **17,014ms**. This includes generation and scrolling; it does **not** measure visual-generation-finished→return or spoken-result latency.
- `pages_collected=1` counts one distinct whole native body, not one physical viewport. It is not a claim about measured scroll count or three-screen coverage.
- A separate unique short task `cc728e70-b410-49b4-b960-f9758bf9a694` submitted once and retrieved its exact requested text, but ended partial/`device_locked` at collection39,819ms. Its completion and coverage were **not** established. No retry or resend after the lock.
- Long-answer content and automatic terminal completion are now verified for this test. Perceived completion delay, uninterrupted short-answer acceptance on this version, Voice return and continuous-use reliability remain open; **P2 ACTIVE**. Stop device testing after the lock and observe ordinary use before claiming broader acceptance. Earlier pending-device/release statements below are historical.


## Installed v0.1.53 evidence

Task `bbd87849-9e8f-47a9-8d4b-11523b5409c9` submitted one unique question, then returned
`partial` / `reply_scroll_stalled`, collection_ms=6554, pages_collected=1,
coverage=unconfirmed. No automatic resend. Returned text contained all 40 requested items
(201 through 240), with every double/triple value checked, 1,238 Unicode code points,
no output truncation. This establishes test-body retrieval, not successful automatic completion
or perceived visual-to-speech latency.

Terminal metadata still showed a forward-scrollable foreground native history and the same
1,238-character body, but no visible question/completion controls. The exact-question anchor
had been recycled out of the tree. An optional later metadata-only inspection was denied by
local operation permission; it was not retried or enabled remotely.

## Correction

The existing collector treated three unchanged polls after its first scroll as a stall and
never issued another scroll. Native accessibility exposes the whole response text across
multiple viewports, so equal text alone cannot establish failed movement.

- Mark only body text with exact native question/response ownership as a whole native body.
- After anchoring that invocation's body, allow bounded further forward scrolling when the
  whole collected body is still exactly equal. Recycling the question after scrolling does
  not erase this invocation-local association. Generic unchanged viewports retain their stall guard.
- A visible verified completion footer is still mandatory. Repeated actions do not establish
  coverage; max24 actions, existing deadline/overlap/baseline/turn/foreground checks remain.
- Recheck cancellation, permission, lock and the exact region before each action. If forward
  scrolling disappears, wait for layout/footer. No duplicated body or inflated page count.
- Regressions cover four native viewports, recycled question, delayed footer, action limit,
  unrelated replacement, cancellation and unchanged generic-page stall.

## Verified delivery

- PR #99 merged as `1e580e353d0da3f9cc6c1778f79a81a482b7cdd6`.
- Android XML reports: **2,477 tests, zero failures/errors/skips**. Includes collector19,
  Android adapter17 and native parser7 tests.
- PR Security CI `39109314270`, main Security CI `39109579971`, signed native runtime
  probe `39109314278`, Signed Release `39109911610`: all succeeded.
- [v0.1.54/code55 signed APK](https://github.com/al-hub/closepaw/releases/download/v0.1.54/closepaw-aa-bridge.apk)
  independently downloaded: 116,381,677 bytes; manifest/package/version/code, checksum file,
  GitHub asset digest, APK v2 signature and previous signing identity verified. Both bundled
  native runtimes are AArch64 and retain their previous hashes. Immutable release tag resolves
  to the merged code SHA above.
- APK SHA-256: `61f0113a3d34cb8576b2c0c3c7e5f2c494c90f700b77619e426acbe3a89ab38e`.
- Certificate SHA-256: `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`.
- OpenAI runtime SHA-256: `d25d4f8977ec93089dfed41667acd91ab6174042e8c6943428a150dca8895288`.
- Cloudflared SHA-256: `5a16aea0f429f1dfc635b7d61c21cd1c357f4d0e7b8992d16cadf160f7166f76`.

**Installed v0.1.54 acceptance pending. P2 ACTIVE.** The v0.1.53 body-content check passed,
but automatic terminal completion failed; prior long-answer failures stay recorded. Next
acceptance must use a fresh unique long question, preserve Gemini foreground/unlocked,
verify first/last and all items, terminal completion/coverage, and report timing with its limits.
Do not equate collection_ms or completion-evidence timing with visual-to-speech latency.
