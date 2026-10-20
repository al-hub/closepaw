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
| Font/IME/small-height layout | Pass (corrected source); device Not Tested | Re-review of PR #123 candidate `d6224d20`: `fitAvailableHeight()` hides optional actions below 320dp and header below 176dp; fixed emergency row, one-line button labels and screen-based maxY clamp replace the overflowing IME-first placement. Added 200% font/100dp viewport measurement test is pending CI execution at this review. Extremely small remaining app area may overlap the IME to preserve emergency access; no universal device fit claim. |
| TalkBack focus stability | Pass (corrected source); device Not Tested | Manual and response buttons are now created once and updated by visibility. `statusUpdateRetainsAccessibleControlInstances` asserts identity stability; pending CI execution. Actual focus traversal/live announcements remain Not Tested. |
| Motion safety | Pass (source) | Pixel decoration checks interactive/attachment/animator/touch exploration state, clears geometry on resize and user control; reduced motion uses expiry invalidation rather than static frame loop. Actual lifecycle/performance remains Not Tested. |

### Contrast calculation evidence

Calculated relative-luminance contrast from explicit opaque sRGB token pairs (not a screenshot or full UI audit): light body/background 14.58:1; muted/background 6.00:1; faint/inset 4.61:1; light primary button text/fill 5.70:1; light secondary text/fill 5.63:1; dark body/background 16.98:1; dark faint/inset 6.82:1; dark primary text/fill 9.47:1; native panel text/fill 12.12:1 light and 13.47:1 dark. These selected text pairs exceed 4.5:1. Alpha-composited/disabled/gradient/outline states and every screen have not been exhaustively evaluated.

### Required follow-up before closing this review

1. The two initial P1 findings were corrected and independently re-reviewed at source level. Run the added instrumented measurement and identity checks in CI; failures require re-opening the relevant finding.
2. Render light/dark, active/waiting/paused/manual/stopped, 200% font and compact landscape states. Do not infer rendered quality from source approval.
3. Physical #119 acceptance remains open: Pause/Resume/X, IME/rotation, TalkBack and non-interference. Keep Pass / Fail / Not Tested evidence separate from source review.

### Compact-mode acceptance boundary

Automatic compact mode prioritizes Pause/Resume and Stop. The auxiliary Direct Control and waiting-response buttons can be hidden on constrained screens; Pause still transfers control safely, and physical Resume still requires fresh observation. Minimal mode also hides the visible status heading while retaining the full session status in the primary control accessibility description. Confirm that this tradeoff is understandable in rendered/physical acceptance; it is a deliberate safety fallback, not proof that every action remains visibly available at every viewport size.

## Rendered evidence review — first CI renderer output

The reviewer visually inspected the downscaled four-panel montage `panel-review-thumbnail.jpg`, generated from the actual CI native View rendering outputs `panel-dark-font-1.0.png`, `panel-dark-font-2.0.png`, `panel-light-font-1.0.png`, and `panel-light-font-2.0.png`. These are synthetic offscreen Android panel renders, not screenshots of a live accessibility overlay operating another app.

| Observation | Verdict | Consequence |
| --- | --- | --- |
| Light and dark surfaces | Pass (render) | Matching blue/cyan palette, consistent corner shapes, restrained borders, clear reading contrast, and generous button areas are visible in both modes. |
| Normal-size Korean labels | Pass (render) | Pause, Stop, Direct Control, Response Complete and Collapse are legible; row separation and button bounds are visually clear. |
| 200% Pause label | Fail in first rendered evidence | Both light/dark output visibly truncate “일시정지” to “일시정...”. This matters because the primary safety action must remain explicit. Source-only large-font approval does not override this rendered defect. |
| 200% heading and auxiliary buttons | Pass with limit (render) | Heading wraps across two lines and auxiliary button labels remain legible in this particular 288dp waiting-state panel. It does not establish all viewport/state/font combinations. |
| Move affordance | Improvement opportunity | Heading is a drag handle in code/accessibility description, but has no clear visual grip. A subtle handle or short move instruction would improve discoverability; this does not block emergency control access. |

### Render defect correction reviewed in source

The subsequent `RemoteControlPanel` source uses two-line icon/Korean labels at fontScale ≥1.5, 14sp button type and 4dp padding for the primary Pause/Resume and Stop controls. This preserves the actual scaled font rather than disabling font scaling. `RemoteControlPanelRenderingTest` now asserts zero ellipsized lines for every visible button in the four render variants. The short-viewport test normalizes line breaks in label matching.

**Correction verdict: Pass (source), corrected render Not Tested at this review.** Re-render the four images and visually verify the complete Korean primary label before changing the first-render failure to resolved. No other blocking visual issue was found in the available four images. Main app screens, the independent X, actual target/path animation, active/paused/stopped states and physical interaction were not present in this montage and remain outside this rendered evidence.

## Final corrected-render verification

The reviewer directly inspected `panel-corrected-thumbnail.jpg`, the downscaled montage of the four corrected native panel PNGs, for PR #123 head `308baea`. At 200% font scale, both dark and light panels now show the complete **일시정지** and **중단** labels beneath their icons with no ellipsis or clipping. At normal font scale, the same controls retain their single-line labels and consistent layout. The earlier primary-label rendered defect is **resolved — Pass (render)**. Light/dark alignment, readable contrast, rounded card consistency and generous visible button areas also pass for these four waiting-state panel renders.

Engineering CI evidence reported for this head: Security CI `39393879629` succeeded; UI CI `39393879612` succeeded with six tests, zero-ellipsis checks and four PNGs, artifact `12251181863`. The Product UX reviewer independently verified the rendered images, not execution logs; the engineering report remains the authority for test execution details.

**Final Product UX review conclusion:** source corrections and the available corrected panel renders pass this AI expert-role review. No outstanding blocking Product UX issue remains in the reviewed source/render scope. Physical human acceptance, live accessibility-overlay routing, actual TalkBack traversal, full app screens, IME/rotation/foldable operation and motion/performance remain **Not Tested** by this reviewer; #119 physical acceptance remains open.

## Final executed delivery evidence (2026-10-20)

PR #123 merged as96cc7013a4f00bb7e2c3c5c648360740785e33ae. PR Security39393879629/UI39393879612, main Security39394905098/UI39394905106 and Signed Release39395656700 all succeeded. Main reports:2632 JVM tests/0 failures/errors/skips; lint0 errors/119 warnings/4 info; Python55+8;6 native View UI tests and4 corrected render files. Actual normal/200% Light/Dark panel renders were reviewed; the large-font Pause truncation was fixed and guarded by no-ellipsis assertions. Original signer, manifest0.1.70/code71, source/tag, native runtimes, checksum and anonymous public APK download passed; proof artifact12252261387. [Full final evidence and acceptance matrix](premium-ai-human-ux-v0170.md). This final result supersedes initial pending-CI entries; local Gradle/pytest execution and an automated local RED were not available. Physical/human acceptance and measured device performance remain Not Tested.
