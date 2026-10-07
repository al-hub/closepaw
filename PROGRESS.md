# ClosePaw Progress

This is the live project milestone board for the ChatGPT Android control path.

Progress work follows one rule: **implementation and this document move together**. A milestone is complete only when its real-device user experience has been verified.

## Current position

```text
Completed: 1 / 10

✅ P0 · MCP Connection
🟡 P1 · Browser Read MVP      ← ACTIVE
⏭ P2 · General Read
⬜ P3 · Kakao Read
⬜ P4 · Termux Execute
⬜ P5 · PC Continuity
⬜ P6 · Toss Read
⬜ P7 · Reliability
⬜ P8 · App Extensibility
⬜ P9 · Portability
```

## Status vocabulary

- `DONE` ✅ — verified end-to-end
- `ACTIVE` 🟡 — current milestone
- `NEXT` ⏭ — immediate next milestone
- `LATER` ⬜ — intentionally deferred
- `BLOCKED` 🔴 — cannot progress without resolving a concrete blocker

## P0 · MCP Connection — DONE ✅

**Goal:** ChatGPT Android Text/Voice can call ClosePaw on the same phone through custom MCP.

Verified baseline:

- [x] Android ChatGPT text → ClosePaw `get_status()`
- [x] Android ChatGPT Voice → ClosePaw `get_status()`
- [x] Same-phone ClosePaw returns actual app/version status
- [x] No laptop, KUM, Termux, custom Voice UI, or external controller required

## P1 · Browser Read MVP — ACTIVE 🟡

**User-visible goal**

> “삼성 인터넷 현재 페이지 읽어줘.”

ChatGPT Voice/Text → ClosePaw → Samsung Internet → visible page content → same ChatGPT conversation.

**Keep P1 deliberately small.** Read the currently visible page first. Add scrolling/full-page collection only if real-device use proves it is necessary.

### Implementation

- [x] Progress naming and live board established
- [x] Capability-style protected MCP path for new read-capable builds
- [x] `read_app(app="samsung_internet")` MCP contract
- [x] Samsung Internet launch via existing Android platform
- [x] Visible Accessibility-tree text capture
- [x] Fail closed if Samsung Internet is not actually foreground
- [x] CI passes
  - Security CI passed after the compile-fix rerun
- [ ] Debug/release APK installed on the target Galaxy
- [ ] ChatGPT Text E2E browser-read validation
- [ ] ChatGPT Voice E2E browser-read validation
- [ ] Decide from real use whether scroll/full-page collection is needed

### P1 completion criterion

P1 is `DONE` only when a real Galaxy can complete:

```text
ChatGPT Voice
→ “삼성 인터넷 현재 페이지 읽어줘”
→ ClosePaw read_app
→ Samsung Internet content
→ ChatGPT response
```

with useful accuracy and acceptable latency.

## P2 · General Read — NEXT ⏭

Reuse the proven P1 path for:

1. Chrome
2. Gemini

Completion means the same minimal READ approach works across all three targets without building a second agent inside ClosePaw.

## P3 · Kakao Read — LATER ⬜

Read a bounded KakaoTalk conversation range and let ChatGPT summarize it. Add scrolling/dedup only as real use requires it.

## P4 · Termux Execute — LATER ⬜

Expose the already-proven Termux execution path through MCP with the smallest safe interface.

## P5 · PC Continuity — LATER ⬜

Termux → SSH → PC. Add persistent tmux/session behavior only when interruption/resume is proven to matter in real use.

## P6 · Toss Read — LATER ⬜

Limited financial READ only. Return the requested value or summary, not broad raw screen data. No transfer/payment/auth actions.

## P7 · Reliability — LATER ⬜

Fix the failures that appear in daily use: latency, recovery, UI changes, Voice continuity.

## P8 · App Extensibility — LATER ⬜

Generalize AppProfile/registry only after repeated app integrations reveal a real common pattern.

## P9 · Portability — LATER ⬜

Stable endpoint, reboot recovery, onboarding, and another Android-device validation.

## Working rule

Do not move work forward because code exists. Move the milestone only when the user-visible path is verified.

During Progress work, report in chat using the compact form:

```text
현재: P1 · Browser Read MVP
완료: <what just became verified>
다음: <one immediate next action>
```
