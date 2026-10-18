# Identical whole native body across scrolls — v0.1.54 / code55

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

CI/release/new installed-device acceptance pending. **P2 ACTIVE.** The v0.1.53 body-content
check passed, but automatic terminal completion failed; prior long-answer failures stay recorded.
