# #165 integrated candidate — 2026-10-26

## Implementation and independent review

- #173/#174/#175 exact-head Security CI and Premium UX UI success confirmed; independent static code/security review by a separate Work reviewer found no blocking findings. Merged as f6826144, 23f3d14e, 64357eb6 respectively.
- Common read defaults now admit an OS-selected enabled IME or the selected general HTTP+HTTPS browser only during an explicit active session. Bundled/user classification and runtime denial remain authoritative; this grants no interaction permission. Unknown apps without this evidence remain CAUTIOUS. Browser selection is established through public intent resolution, not privileged role-holder APIs. No default-app change is made.
- MCP query/task image delivery metadata is computed from the actual live revocation-checked image block. Empty/non-object nested envelopes use the same selection rules as the image cache.
- Literal key results require exact text and expected collapsed selection, including replacement, supplementary character deletion and Hangul composition.
- Visual named layout controls use the existing actual-image/label/IME/geometry/native guard path. Submission and IME-picker controls remain refused. Field text/selection must be preserved and two disjoint existing character-key locations must change. Each subsequent visual key requires a new image; no automatic visual layout sequence or replay.
- Independent review of the layout candidate found one false confirmation risk from nested parent/child key bounds. Requiring disjoint rectangles fixed it; independent re-review found no remaining blocker. Separate independent reviews of the read/image and cursor changes found no blocking issues. Static review is separate from CI and physical acceptance.

- Concurrent PR176 display app gestures are included in the same candidate: targets stay inside application bounds and outside IME; gesture paths require certified pixels and current display generation. No separate version95 release should be merged from176.

## Validation status at submission

| Gate | Status | Evidence/limit |
|---|---|---|
| Existing PR173 CI/UI | PASS | 40322973237 / 40322973249 |
| Existing PR174 CI/UI | PASS | 40324467336 / 40324467277 |
| Existing PR175 CI/UI | PASS | 40324756837 / 40324756875 |
| Local release/trust scripts | PASS | 46 unittest cases on integrated base64357eb6 |
| Whitespace/conflict checks | PASS | git diff --check |
| Local Android/JVM compilation | BLOCKED | Gradle distribution unavailable through this environment's network; no assertions executed |
| Local Python bridge pytest | BLOCKED | pytest unavailable; CI retains these tests |
| New candidate Security/UI CI | NOT_TESTED | To be confirmed for exact PR HEAD and final main |
| New APK/public signature/install | NOT_TESTED | One integrated release only after required gates |
| Two-app display/IME image/coordinates | NOT_TESTED | Must receive and inspect actual MCP image blocks |
| Layout/text/rotation/physical X/Termux | NOT_TESTED | Device acceptance required |
| Clipboard on background service | BLOCKED | No safe staging when OS cannot prove readable current provenance; no bypass or automatic restoration |

Candidate version0.1.94/code95. Do not close #165 until every required gate is met.
Existing0.1.93/code94 phone response is baseline evidence, not this candidate's acceptance.
No TalkBack/foldable scope is reintroduced. Follow-up results are recorded in issue165 and the candidate PR so main remains fixed during signing.
