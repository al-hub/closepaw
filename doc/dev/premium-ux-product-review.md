# Premium interaction — Product UX review

Date: 2026-10-20. Reviewer: AI role, Senior Product UX Designer. This is an AI role-based review; no human expert participated. Baseline: `e6404b5de1e1047caf56c7b4867032b6490e240e` (v0.1.69).

## Phase 1: observed baseline and UX audit

Code inspected: `AGENTS.md`, `CLAUDE.md`, `RemoteControlIndicator.kt`, `ControlSession.kt`, `ControlSessionOperations.kt`, `StatusIslandCompose.kt`, `ActionVisualizerCompose.kt`, `Theme.kt`, `Tokens.kt`, `Motion.kt`, `ChatHeader.kt`.

| Existing journey | Evidence in code | Product UX finding |
| --- | --- | --- |
| Start a session | Explicit start; IDLE/ENDED → ACTIVE | Preserve explicit authorization and session identity; a prettier interface must not restart a terminated session. |
| Observe/operate another app | Non-touchable full-screen decoration, separate control windows | Good input-separation foundation. Keep decorative layers outside input dispatch. |
| Pause | 56dp icon button; state/epoch invalidation | Touch size meets basic guidance, but the icon alone is insufficiently explicit. Add Korean label and visible paused state. |
| Resume | Same button becomes play; re-observation before ACTIVE | Preserve stable position and truthful intermediate feedback. Do not show success until re-observation completes. |
| Wait for human | Breathing border; same button changes to check mark | Add clear “사용자 확인 대기” state and “선택 완료” label; avoid an unexplained third icon meaning. |
| End | 48dp X at upper right; session end blocks new commands | Keep continuously reachable. Explain that the session ends; completed external effects are not undone. |
| Target feedback | Short ring and rectangle around supplied bounds | Separate intended target highlight from attempted gesture and verified result. Current highlight alone does not establish actual action completion. |
| Read state | Canvas-drawn text at fixed top coordinate | Text lacks ordinary view semantics and can clip with font scaling. Promote primary state to accessible text in the control panel. |
| Reposition | Pause avoids keyboard by polling IME bounds | Fixed location can still cover content. Add explicit drag affordance, clamping, and compact mode. |
| App-owned screens | Existing Material 3 theme uses warm paper/claw colors | Existing design is coherent but differs from requested Calm Intelligence palette. Use shared blue/cyan tokens across app and overlays rather than a one-off overlay skin. |

## Phase 2: first design review

**Conditional design approval:** implement the following priorities while preserving the existing session gate and native result checks.

1. Persistent control card: accessible state heading, one short action sentence, dominant 56–64dp Pause/Resume button in the same slot, separate Stop and Direct Control actions, and a clearly labeled compact/expand affordance. Keep X at screen upper right.
2. Never put essential controls inside a decorative Canvas. Every control needs text, accessible description, enabled/disabled state, touch feedback, and at least 48dp bounds. Aim for 8–12dp gaps.
3. Map visible states to real events: preparing, observing, executing, waiting for app, waiting for human, paused/manual, complete, and failed. No fabricated percentage, animation-based success, or unsupported “thinking” inference.
4. Explain user control in one sentence: “일시정지 · 직접 조작할 수 있습니다.” On resume: “현재 화면을 확인하고 있습니다.” On failure: specific recovery next step, without raw internal diagnostic codes.
5. Distinguish Stop from X: Stop cancels the current work and blocks queued actions with a persistent stopped state; X terminates the session and removes related overlays. If the engine cannot safely distinguish them, disclose the implemented boundary and do not imply resumability after termination.
6. Manual Takeover must block automation before switching its appearance. Resumption requires the physical Resume control and fresh observation; no queued replay.
7. Give intended target a low-opacity outline. Give dispatched action a short cyan ripple/trace. Show outcome separately after native result/observation; uncertain outcome remains uncertain.
8. Reduce obstruction: allow a compact panel, drag only from a labeled handle, clamp to usable screen bounds and keyboard edge after rotation/unfold. Keep emergency X separately available and full-size in compact mode.
9. Ambient motion is subordinate to content: slow, low-contrast breathing; no rapid flash; no infinite motion in paused/manual state. Disable motion when screen is off and honor reduced motion. Waiting-for-human should be visibly distinct through words and icon, not just color.

## Scenario acceptance matrix

All runtime items below are **Not Tested** at first review. Static design recommendations are not device acceptance evidence.

| Scenario | Required user-visible behavior | Evidence required |
| --- | --- | --- |
| Start / launch / switch | Preparing → actual app action → result or uncertainty | Actual session and action events; screenshot/recording |
| Tap / long press / swipe / scroll / drag | Exact native point/path; intended vs attempted vs result distinct | Gesture coordinate comparison and visual observation |
| Type / send | Field-only emphasis; no password or secret text in overlay; guarded send | Safe-field tests, redaction/guard tests |
| External response / read | Plain-language waiting/reading state; no fake progress | Engine event assertions and device check |
| Pause / manual | No new automatic effect; stable Resume slot; motion stops | Queue/epoch tests and physical interaction |
| Resume | Re-observation first; no completed-action replay | Freshness/epoch tests and device interaction |
| Stop / close | Queued effects blocked; close removes windows; stale callbacks cannot revive UI | Cancellation/race tests and physical interaction |
| Approval | Explicit requested decision; AI cannot press confirmation for user | Native control ownership test |
| Network / accessibility / permission error | Readable cause and next step; no false success | Failure tests and visible recovery check |
| Lock / unlock | No hidden rendering or autonomous resume after lock | Lifecycle/lock tests and actual device check |
| Complete | Brief verified outcome, then quiet state; no unsolicited new work | Actual completion event and visual check |

## Phase 4: second review status

Second source review performed against the working implementation on 2026-10-20. Files additionally inspected: `RemoteControlPanel.kt`, `PremiumControlDecoration.kt`, `PremiumMotionPolicy.kt`, `AgentService.kt`, `AndroidControlPort.kt`, updated theme/color/shape and chat ambient surface, and `RemoteControlPanelTest.kt`.

**Scope:** independent AI Product UX source review only. Emulator screenshots are awaiting CI at this point. No actual rendered screenshot, physical touch, TalkBack session, IME/rotation/fold interaction, smoothness, fatigue, or battery evidence was available to this reviewer.

| Criterion | Source review result | Evidence and limit |
| --- | --- | --- |
| Large explicit controls | Pass (source) | Native buttons use 56dp minimum height, 15sp Korean labels, ripple and haptic feedback. Actual measured layout at enlarged fonts remains Not Tested. |
| Stable pause/resume location | Pass (source) | Same primary button changes label by actual session state; re-observing disables it and removes app-covering panel before fresh observation. |
| Human control priority | Pass (source) | Manual action calls `manualTakeover()` → PAUSED + interrupt; Stop calls `end()`; X ends and hides windows. Session state remains authoritative. Race handling requires engineering tests. |
| Stop versus X | Pass with disclosure | Both terminate the control session; Stop retains a stopped-result panel, X also dismisses overlays. Stop is not a resumable task-only cancellation. This boundary must be retained in final documentation. |
| Real action versus visual success | Pass (source) | Tap effect only emitted after accepted dispatch. Node action shows target bounds and acknowledgement, no invented finger path. Decoration does not claim verified external success. Native result checks remain separate. |
| Decorative input isolation | Pass (source) | Full-screen layer uses NOT_TOUCHABLE; bounded panel and X alone receive touch; overlap checking changed to rectangle intersection. Physical routing remains Not Tested. |
| Calm visual consistency | Pass (source) | Shared blue/cyan light/dark tokens, rounded shape tokens, cached ambient background; panel and decoration use matching calm palette. Visual polish remains Not Tested pending screenshots. |
| Font/IME/small-height layout | Fail at review; correction requested | `placePanel()` prioritizes IME minY even when the panel exceeds remaining height. On small landscape or enlarged font, the panel can extend beyond the screen top. Add available-height constraint/scrollable auxiliary content and preserve emergency controls. |
| TalkBack focus stability | Fail at review; correction requested | `update()` removes and recreates secondary controls on every feedback update; this can lose focus. Reuse buttons and update visibility/state instead. Actual TalkBack operation remains Not Tested. |
| Motion safety | Pass (source) | Pixel decoration checks interactive/attachment/animator/touch exploration state, clears geometry on resize and user control; reduced motion uses expiry invalidation rather than static frame loop. Actual lifecycle/performance remains Not Tested. |

### Contrast calculation evidence

Calculated relative-luminance contrast from explicit opaque sRGB token pairs (not a screenshot or full UI audit): light body/background 14.58:1; muted/background 6.00:1; faint/inset 4.61:1; light primary button text/fill 5.70:1; light secondary text/fill 5.63:1; dark body/background 16.98:1; dark faint/inset 6.82:1; dark primary text/fill 9.47:1; native panel text/fill 12.12:1 light and 13.47:1 dark. These selected text pairs exceed 4.5:1. Alpha-composited/disabled/gradient/outline states and every screen have not been exhaustively evaluated.

### Required follow-up before closing this review

1. Engineering fixes for available-height layout and secondary-button identity, followed by source re-review.
2. Render light/dark, active/waiting/paused/manual/stopped, 200% font and compact landscape states. Do not infer rendered quality from source approval.
3. Physical #119 acceptance remains open: Pause/Resume/X, IME/rotation, TalkBack and non-interference. Keep Pass / Fail / Not Tested evidence separate from source review.
