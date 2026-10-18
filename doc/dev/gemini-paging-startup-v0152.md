# Gemini paging startup recovery — v0.1.52 / code 53

## Real-device finding (2026-10-18)

v0.1.51/code52 installation was confirmed through device status. Short task
`7695366c-9717-4dd2-8d93-687a835b5fb5` succeeded with complete coverage and one send.
Collection took 23,359 ms; first semantic completion observation was 22,774 ms, leaving
585 ms until collection completed. These timestamps do not establish when Gemini visually
finished or when ChatGPT spoke, so perceived latency improvement is not yet accepted.

Long task `c40f696e-d409-49bc-8456-3d037aa34ad5` confirmed submission but failed after
783 ms with `reply_end_unconfirmed`: the current question was visible, no answer/footer was
available yet, forward scroll was unavailable, and backward scroll was available. This is
an ordinary pre-generation layout, not proof of the answer ending. No question was resent.
A later single-screen accessibility read exposed the generated long body through its last
requested item; that read does not validate automatic paging or completion attribution.

## Correction

- Actual native history exposes one long response TextView and a toolbar whose RecyclerView
  attachment order can precede the current question/body. Paging uses screen top/left geometry
  when available, instead of assuming attachment order is document order. Adds the observed
  native user-message ID to later-turn guards. Geometry-less fixtures retain existing order.
- Empty body at the submitted question waits within the original deadline instead of failing.
- A temporarily replaced startup region can recover before body acceptance or any scroll.
- A missing forward action after body capture waits for further content, footer or timeout;
  it is never treated as completion evidence.
- Stable end-of-body waiting does not inflate pages_collected.
- Existing foreground, lock, consent, cancellation, stale body, overlap, character and scroll
  limits remain. Scroll-capability booleans are added to redacted node diagnostics.
- Regressions reproduce delayed body/footer, empty timeout, startup-region replacement and
  repeated final-body observations after an actual forward scroll.

CI, signed publication and installed-device acceptance are pending. The v0.1.51 long-answer
acceptance failure remains open until a later APK is verified on the Galaxy.
