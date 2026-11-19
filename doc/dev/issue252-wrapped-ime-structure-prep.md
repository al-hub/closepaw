# #252 Samsung IME wrapped recommendation regression preparation

Scope: test fixtures and decision notes only. No production selector, operation,
permission, diagnostic PR, version, APK, or device change. Refs #252.

## Evidence and hypotheses

On signed v0.1.127/code128 the recommendation captions had different immediate
parents and different parents one level above. A similarly labelled physical
key was a wrapper with a non-actionable inner label. Candidate queries returned
no consonant, one vowel, and one image-only Space, with zero touch or search
effects. This does not establish the actual common ancestor or remote pixel mask.

The new fixture preserves the two distinct wrapper levels. Its higher links,
additional ancestor rectangles and node IDs are synthetic. It represents
three deliberately separate cases:

| Case | Baseline result | Decision after diagnosis |
| --- | --- | --- |
| Higher path unknown | Consonant rejected, vowel and image Space remain visible. No dispatch. | No promotion without owned structure. |
| Hypothetical dedicated container above, separate from the owned physical row | Same baseline rejection. This is the unimplemented positive case. | Only if the actual report supports this path, use the prepared exact-effect oracle with the dedicated fixture. |
| Shared IME root or ambiguous path | Reject before touch. | Preserve refusal. A higher shared ancestor alone never authorizes exclusion. |

The negative cases also cover no actual key, two actual same-label keys, a
container overlapping a physical row, foreign ownership and a cyclic ancestry.
The same-parent control proves the current layout can complete only the three
intended keys while recommendations change. Other controls require a visible
crop to fail when the real key boundary is masked and stop after the lone
confirmed first key when the physical row or editor changes during refresh.

## What unlocks a production change

1. Bundle read-only PR #265 only with a separately selected normal update.
2. On the Galaxy, run the local Settings diagnostic once with the prior no-input
   procedure. Correlate the caption paths and lowest common ancestor with the
   known recommendation placement and independently observed physical row.
   Establish owned, dedicated ancestry, distinct from the IME root, outside and
   disjoint from the physical rows. A report classification alone is not proof
   that the right captions were correlated. No raw image, suggestion text or
   editor value is needed in a public record.
3. If evidence is incomplete, record precisely which relationship is missing
   and stop. Do not search deeper by a fixed number of levels, allow all common
   ancestors, match an OEM, text, size or coordinates as identity, or release
   a speculative selector. A shared root is not a dedicated container.
4. Once evidence is sufficient, model the real topology in the fixture.
   Replace the dedicated baseline expectation with
   `assertHangulSequenceWithSuggestionRefresh` on the confirmed shape. The
   oracle checks exact physical target IDs, the absence of recommendation
   touches, stable renewal when only recommendations change, three completed
   keys and one image-only Space with the full value and caret. Keep the unknown
   and refusal cases, and exercise the same confirmed topology in the renewal
   safety tests. A wide key corroborated by its physical row must stay.
5. Make the smallest shared classification change for initial/renewed
   candidates and physical layout comparison. Keep local structure evidence
   separate from fresh remote pixel authorization. A partially masked
   recommendation cannot authorize a touch, and the selected physical key
   still needs all of its certified bounds, valid ownership and existing
   live session/editor and no-replay checks.

The test branch is not a fix or an acceptance PASS. Do not merge or release it
as a way to obtain a diagnostic APK. After independent source review, required
PR/main CI and a verified signed final APK, the single Galaxy request is
`ㄱ → ㅏ → Space`. It must report `completed_keys=3`,
`confirmed_image_fallback_spaces=1`, `app_result_verified=true` and
`outcome_unknown=false`. A separate read must confirm the same editor, exact
value `가 ` and caret `2/2`, with no search submitted, before #252 can close.
