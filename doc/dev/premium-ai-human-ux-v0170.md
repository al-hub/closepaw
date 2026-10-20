# ClosePaw Calm Intelligence — v0.1.70 / code71

## Scope and baseline

2026-10-20 user-authorized implementation of Premium AI–Human Interaction UX; tracks #122 and extends #119. Baseline main e6404b5d, APK v0.1.69/code70, #118/PR120/PR121 result guards retained. AGENTS.md, CLAUDE.md, development.md, WORKS_HANDOFF.md and issues114–119 reviewed. Root AI.md does not exist. Camera, locked UI, old HOLD Notes/Clock work and long-run #117 remain excluded. Existing accepted Wi-Fi/SSH/Termux/Gemini behavior is not repeatedly exercised.

The four reviews are **AI expert role reviews**, not reviews by human professionals. Source inspection and emulator rendering do not establish physical-device acceptance or commercial readiness.

## UX audit and interaction specification

Baseline had a square icon-only pause button, icon-only X, fixed position, Canvas-only status and one target ring. It already had a persisted user-priority ControlSession, epochs, native validation and guarded physical resume. Reuse those mechanisms instead of creating a competing control engine.

Journey: explicit start → security observation → target validation → native effect submission → post-observation/result verdict → next command or user wait. Any active state accepts pause, takeover and end. A tap ripple means submission, not proof the application reached the goal. No invented progress percentage, completion time or finger path.

| Actual state/event | Presentation | Control behavior |
|---|---|---|
| Active / observation | Slow blue/cyan edge light and readable Korean status | Pause, Stop, Takeover, X available |
| Validated target | Short dashed region, “대상 확인” | Planned region, not an executed finger touch |
| Submitted physical tap | Soft exact-point ripple and region | Native callback/result remains separate |
| Semantic click/input/long-click | Solid target region after native acknowledgement | Does not invent a physical contact point or hold duration |
| Semantic scroll | Static direction cue in known bounds | No invented physical swipe path |
| Actual injected swipe | Light travels the injector’s unchanged endpoints and duration; separate afterglow | No success inference from motion |
| Waiting for user | Amber breathing edge plus explanation | Pause remains present; separate response control |
| Pause / Manual | Static subdued light; actual state text | Epoch invalidation, no queued effect replay |
| Physical Resume | Panel removed, “재개 전 화면 확인” | Serialized fresh security observation, X remains |
| Stop | Ended gate plus compact stopped result | Cannot resume old session |
| Close / X | Ended gate and windows removed | New explicit session required |
| Failed / unknown | “결과 미확인 · 재관찰 필요” | Preserve unknown outcome, never blind replay |
| Screen off / lock / disposal | Remove effects, stop decorative work | Existing lock refusal and pause policy retained |

Stop and X intentionally share the irreversible **session-ended** engine gate in this version. Stop retains a result panel; X closes it. Neither force-stops Android or rolls back an external app’s completed effect. Manual takeover uses PAUSED with an explicit Korean label; it does not silently resume.

## Design system

Material3 continues to own main app typography, shapes and controls. Stable token names preserve callsites while adopting cool porcelain/deep navy, blue/cyan accents, muted amber waiting and readable errors. Geist body typography and the existing identity font remain. Main chat uses cached radial ambient depth, with no perpetual background animation or bitmap texture generation. Common corner radii are12/18/24dp.

| Token | Light | Dark |
|---|---|---|
| Background | #F3F7FC | #09121F |
| Surface inset | #E6EEF7 | #122236 |
| Main text | #12243A | #EDF4FF |
| Secondary text | #4D6077 | #B5C7DE |
| Primary | #135FC0 | #82BCFF |
| Secondary | #006D7A | #73D4DD |
| Primary container | #D6E7FF | #183A61 |

Native accessibility overlays use compatible opaque/translucent gradients and RippleDrawable, rather than importing a second Compose lifecycle. Panel text/background contrast and selected main token pairs are measured in the product review; whole-app accessibility is not certified by those pairs alone. Primary controls are at least56dp high, emergency X56dp,8dp row gaps, rounded16/24dp surfaces. Panel status is an accessible TextView with polite live-region updates, while full-screen decoration remains excluded from accessibility traversal. One physical Pause/Resume location, a separate Stop and a clear Manual control reduce ambiguity.

Presentation phases are structured engine events (`PREPARING`, `OBSERVING`, `EXECUTING`, `WAITING`, `COMPLETED`, `FAILED`), separate from control-permission states. Epoch checking rejects a late visual phase after pause or a new session. Verified outcomes use teal; failed/unknown outcomes use a restrained warning accent and recovery text. Remote ChatGPT thinking is not observable by Android, so no invented Thinking signal is displayed. A presentation completion denotes the current verified action only, not the wider task.

## Motion and autonomous touch specification

`PremiumMotionPolicy` bounds target900ms/action720ms at33ms rendering intervals. Low-alpha layered strokes create edge depth without a fullscreen blur. Planned regions use a dashed contour, native acknowledgements a solid contour, point ripple only the actual admitted `NodeTapPolicy` point after dispatch submission. No raw screen text or input content is retained in these effects.

`VisualizationTiming` separates actual gesture duration from400ms afterglow. At most six concurrent injected-gesture effects; no coordinate clamping or curve invention. Semantic scroll directions remain static. Actual injected gestures retain their straight path because changing a drawn curve would misrepresent the real path. Double tap and drag have no new generic MCP operation in this release; a caller cannot infer support from the renderer. No new MCP schema or plugin registration is required.

Effects clear on user control, rotation/size changes, detach and screen-off. System animator disable and TalkBack reduce motion to static feedback. Main ambient depth is static/cached. Decoration errors cannot change an already submitted effect into a retryable failure. The touch pass-through token is released even if cancellation occurs during the50ms window-settling delay. Target validation still rejects password/sensitive/secure/blocked screens. Native button haptics respect Android policy. Main app uses the system Light/Dark selection.

## Scenario coverage

| Scenario | Normal / wait | Failure / recovery boundary |
|---|---|---|
| Start/app transition | Actual observation and launch acknowledgement | Destination postcondition verified; unknown not replayed |
| Tap/swipe/scroll | Intent, submission and semantic/physical distinction | Native cancellation/failure clears or labels result |
| Input/send | Target bounds only, content omitted | Existing consent/security/unknown-send rules retained |
| External response/read | Actual engine status and wait | Existing read policy, no fake ETA |
| Pause/Resume/Takeover | User priority, fresh physical resume | Epoch rejects old observations/late work |
| Stop/Close | Both end old session, distinct UI retention | No automatic restart or rollback claim |
| Approval request | Existing explicit approval policy | Waiting retains emergency controls |
| Network/accessibility/permission | Existing diagnostic state and actionable recovery | No decorative success inference |
| Lock/unlock | No unnecessary rendering; lock refusal | No unlock bypass; physical resume/new start remains explicit |
| Complete/result | Actual result verdict | A wider task is not complete merely because a gesture completed |

## Expert review evidence

- [Product UX audit and review](premium-ux-product-review.md)
- [Visual/motion review](premium-ux-motion-review.md)
- [HCI/control review](premium-ux-hci-review.md)
- [Android engineering/QA review](premium-ux-android-review.md)

Two initial cross-review findings were fixed before acceptance: preserve Pause during WAITING instead of replacing it with response✓; distinguish semantic scroll bounds from the actual injected tap point for panel occlusion. Small-height/large-font placement and stable TalkBack button identity are also explicitly reviewed. Human appearance and physical routing remain separate acceptance items.

## Test strategy and evidence status

JVM tests cover motion time/clamping/fade, manual takeover/terminal epoch behavior and the inherited session/result/security suite. Native panel instrumentation covers waiting controls, takeover, Stop, folding and rendering. A dedicated emulator workflow compiles all androidTest sources and executes the targeted classes, exports rendered evidence. Security CI runs the full Android JVM/lint, Python bridge suites and native/debug build. Existing Signed Release verifies original signer continuity, anonymous published APK checksum/manifest/native runtime/tag identity. No signing key is regenerated.

Local execution limitation: Java17 exists but Android SDK/adb/Gradle distribution and pytest are unavailable; distribution fetch reports network unreachable. Local Gradle commands failed before test execution, **not assertion failures and not a RED TDD result**. `git diff --check` is available. CI results below must be filled from actual runs before claiming pass.

## Final acceptance matrix

| User criterion | Verdict before CI | Evidence / limitation |
|---|---|---|
| Large, clear controls | Not Tested | Source56dp, instrumentation pending |
| Immediate state understanding | Not Tested | Korean TextView/live region, human acceptance pending |
| Pause/Resume/Stop/Close safety | Not Tested | Existing engine reused + new regression tests; CI pending |
| AI/user conflict prevention | Not Tested | Epoch/gates preserved, physical routing pending |
| Natural premium light/waves | Not Tested | Renderer implemented, rendered/human review pending |
| Exact tap point | Not Tested | NodeTapPolicy admitted point wired after submission; device check pending |
| Swipe/Scroll/Drag sync | Not Tested | Actual swipe timing; semantic scroll direction only; no new generic drag |
| Target region display | Not Tested | Dashed intent/solid acknowledgement; device pending |
| Input noninterference | Not Tested | Decorative NOT_TOUCHABLE; bounded panel, actual routing pending |
| Design consistency | Not Tested | Shared main palette + matching native overlay; screenshots pending |
| Long-session eye comfort | Not Tested | Muted/no flashing, no longitudinal user study |
| Accessibility/screen sizes | Not Tested | Source and targeted layout tests; TalkBack/foldable physical pending |
| No functional regression | Not Tested | Full CI pending, inherited device evidence preserved |
| Expert role documentation | Pass | Four AI roles, two-stage source review; human not involved |
| CI/signed APK | Not Tested | Pending actual run and public asset verification |

## Release evidence

Candidate v0.1.70/code71. PR/CI/release/public verification evidence will be recorded after execution. Do not interpret this candidate as released or installed.

## Remaining roadmap

Physical Galaxy appearance/gesture alignment, real overlay touch pass-through, keyboard/rotation/TalkBack/200% fonts/fold transitions, long-use eye comfort, measured frame pacing/CPU/GPU/battery and #117 reliability remain measured acceptance work. No commercial-readiness declaration until those tests are completed. Supported gesture vocabulary is governed by existing operations; generic double tap/drag would require separate safe engine design and validation.
