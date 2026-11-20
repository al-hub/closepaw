# #147 Samsung basic typing candidate and Galaxy acceptance

This work does not submit a search, exercise Voice/app-to-app E2E, or close #147. Use only a dedicated empty Chrome test editor with no submission, matching the device, IME, editor and network of the baseline. Keep the public release test editor empty after installation. These steps describe a future acceptance run, not its result.

## Baseline boundaries

- Galaxy v0.1.133/code134 at source `c72e9d2cf6a04ae478ed06a5061fb11c8275ac9f` was confirmed running before the change. The old ClosePaw visual sequence diagnostic confirmed 2/6 keys, value `가` and caret 1/1, before the first Space was refused. See [device evidence](https://github.com/al-hub/closepaw/issues/147#issuecomment-7038572727).
- Separate prior ADB-only experiment on the same installed version used one keyboard image, reused its coordinates for six touches and returned exactly `가 나 ` with caret 4/4. It was an independent feasibility result, not a ClosePaw path pass and not a time sample.
- The old visual sequence required an empty editor per request and allowed at most six physical keys, without layout changes. It cannot run the mixed benchmark end to end through the same path. Baseline mixed trials and improvement are unmeasured. Do not use ADB or a direct text action as a timing proxy.

## Candidate path

1. Discover the exact installed schema. Obtain a keyboard-only image via `android.screen.observe` with `keyboard_sequence_labels` for the next pending literal group. Inspect the actual image. Only up to six character/Space touches per group. A single-key group is allowed with `reuse_key_map=true`.
2. On a new layout or a missing label, include the observed `steps` with physical candidates and verified image rectangles. The candidate learns geometry of confirmed keys, keyed to the owned editor and physical keyboard state for the session. Omit `steps` only if every key is already known for that state. A cache miss is a no-touch result. Use a *new* observation and supply the missing confirmed steps.
3. Compare the exact accumulated value and end caret with the last confirmed result. On following groups use that exact `expected_prefix`. Session, consent, editor and value must match. Use the existing, separately verified visual layout key for language, numeric/symbol pages or Shift. After a layout action, inspect the destination before sending text and use `layout_witness_labels` with two mode-discriminating physical labels. Example evidence is a pair of letters in the correct language/case or distinct symbols on the intended page. Do not infer the page from the tap response, Space, or digits pinned on the alphabet page. After a single shifted character, inspect the actual returned lowercase state before the next key.
4. Within one layout the fast path reuses coordinate proposals and skips the extra proposal image when the guarded post-tree and cached geometry still match. Native live pixel checks, rotation, IME, windows, editor/caret and final guard still run per touch. A confirmed pre-touch refusal can switch the rest of the bounded group to one-at-a-time fresh image renewal. Only independently verified exact ASCII text and caret in the same editor can settle a lost gesture callback. If composition, editor identity or effect remains uncertain, stop before another touch. Do not replay that key or the prefix.
   If native validation reports a display-window change before touching the pending key, the opt-in route can spend the request's single retry to obtain an independently certified keyboard image with a newer display generation. The current editor, value, caret, key layout and consent must still match. `display_window_recheck_key` identifies the one-based key for which rechecking began. A new invalidation, unchanged image ID or generation, or uncertain delivery stops without replay.
5. At the end verify the whole value and end caret independently. Do not use submit, Enter, clipboard or direct fill as a shortcut. The sequence counters report proposal image renewals and layout map learning, not native screenshot totals. Count native checks separately from the task diagnostics.

## Fixed Galaxy setup after installation

- Confirm the published asset's signed version/code and exact source. Update without uninstalling or resetting data, then check durable installed/current and independently confirm the running app version.
- Prepare isolated Chrome field, Samsung two-set Korean keyboard, empty value and caret 0/0. Stop here for the installation handoff.
- Next test, in order: one diagnostic with `ㄱ → ㅏ → Space → ㄴ → ㅏ → Space`, exact `가 나 ` and caret 4/4. Test both physical Space touches through the visible key route. Record reuse, fresh proof checks and fallback separately.
- Mixed 3-trial acceptance text, including uppercase and one-shot Shift: `오늘 회의는 Zoom으로 3시에 시작! 자료는 A2개.` Use an actual numeric-page transition for the first digit and an actual symbol-page transition for `!`, even if another row offers a shortcut. Verify the resulting page and the return to text, and verify lowercase immediately after the one-shot Shift for `Z`. Preserve the spaces and the final period. The prior lowercase-only 29-character text is not this timing cohort.

| Trial | Result / caret | Elapsed first image → independent final read | Proposal images / native checks | New layout maps / hits | Fallback and first unresolved stage |
| --- | --- | --- | --- | --- | --- |
| 1 | — | — | — | — | — |
| 2 | — | — | — | — | — |
| 3 | — | — | — | — | — |

Use a single calling-side monotonic clock. Reset only the test field between trials. Count failures, blocks and unknown results in the assigned three slots, with no replacement. Use a P50 comparison only if both versions have exact 3/3 under matching conditions. No P95/P99 or numerical improvement from missing evidence.
