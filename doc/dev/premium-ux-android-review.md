# Premium UX Android engineering and QA review

2026-10-20 · AI role-based review. No human Android/QA expert participation is claimed.

## First review: baseline and design

Baseline: v0.1.69/code70; the exact release/source commit is recorded in the delivery report. `AGENTS.md`, `CLAUDE.md`, `doc/dev/development.md`, current native control adapter, service and release workflows were inspected.

- Preserve native policy gates, single-use observations, epoch invalidation, protected-window refusal and physical-only resume. UI feedback must not alter a control result or trigger replay.
- A semantic accessibility `ACTION_CLICK` or `ACTION_SCROLL_*` has a target region, not a measured finger path. Use a region/direction cue. Only an injected gesture may display its actual screen coordinates/path.
- `NodeTapPolicy` may choose a safe point different from the rectangle center. The remote tap marker must use that submitted point.
- Full-screen decorations must retain `FLAG_NOT_TOUCHABLE` and `FLAG_NOT_FOCUSABLE`. Touchable controls occupy separate bounded windows; the actual injected tap point must be checked against them. Semantic node actions do not inject a finger and need not reject an entire large scroll container that overlaps the panel.
- Reobservation removes the touchable panel while preserving the emergency X. Rotation/folding must invalidate old target geometry.
- Animations must stop for screen-off, pause/takeover/ended state, non-screen-viewing context and service disposal. Reduced motion must retain a static, meaningful cue.

## Implemented engineering changes

1. Common injected tap/swipe/long-press visualization starts only after `dispatchGesture` accepts submission and after touch-pass-through settling. Visualization exceptions are contained and cannot turn an already-submitted gesture into a retryable execution failure. Native cancellation clears effects.
2. Exact supplied coordinates are preserved; the previous visualizer edge clamps were removed. Canvas translates by the actual overlay view `getLocationOnScreen` origin, including system insets.
3. Swipe motion duration is distinct from its 400ms afterglow, so the cursor reaches the endpoint at the requested gesture duration. Long-press contraction uses the requested hold duration. Semantic scroll shows a static direction arrow and no moving finger.
4. At most six visual items are retained. One guard job expires items and detaches an empty window; old per-item expiry coroutines are removed. The decorative window remains non-touchable.
5. Screen-off broadcast clears immediately, visible guard checks power/keyguard/render eligibility every 32ms, render-context collector clears on background-context changes, and service/session integration clears on user intervention. A cleared effect is never replayed after resume.
6. System animation disable and existing app reduced-motion setting render static cues. Color does not convey control status alone; the remote panel and status text carry the state.

## Second review: source verification

| Check | Result | Evidence / boundary |
| --- | --- | --- |
| Gesture visualization cannot report app success | Pass (source) | Visualizer callback is guarded by `runCatching`; native result/callback remains authoritative |
| Real gesture geometry retained | Pass (source) | No coordinate clamps, screen-origin translation, injected path uses original endpoints |
| Semantic scroll does not impersonate a finger path | Pass (source) | Static direction branch for `scroll=true` |
| Native target/panel overlap | Pass (source) | The actual injected point is checked against bounded panel windows; semantic actions do not pretend to inject a finger |
| Physical resume/reobservation and X priority | Pass (source) | Panel removed before resume; independent X remains; state epoch gates preserved |
| Light/Dark + large-font panel rendering | Not Tested locally | New instrumented rendering test saves four 288dp native View PNGs at font scales 1/2 |
| Android unit/lint/build | Not Tested locally | Gradle wrapper cannot download its distribution: `Network is unreachable`, before compilation/tests |
| Python bridge regression | Not Tested locally | `python3 -m pytest ...` exits before collection because pytest is absent |
| Instrumented native panel behavior | Not Tested locally | New workflow compiles app/androidTest, then runs panel behavior/render tests on API35 x86_64 emulator |
| Actual overlay routing, TalkBack, IME, rotation/fold | Not Tested | Emulator View tests do not exercise accessibility-overlay routing; physical acceptance required |
| Frame time, battery and prolonged stability | Not Tested | Bounded rendering is source evidence, not a measured frame/battery benchmark |
| Signed APK, checksum, manifest, signer continuity | Pending CI | Existing signed-release workflow retained; actual run IDs belong in the final delivery report |

`git diff --check` passed at review. The new timing regression test checks endpoint arrival independently from afterglow, plus zero-duration/negative-time bounds. Because the local Gradle environment is unavailable, no executed RED/GREEN result is claimed.

## CI and visual evidence

`.github/workflows/premium-ux-ui.yml` triggers for PRs and main/security pushes. It first compiles the debug app and test APK, then runs `RemoteControlPanelTest` and `RemoteControlPanelRenderingTest` on API35 `google_apis` x86_64. Native view screenshots and Android XML/HTML test results are uploaded as `premium-ux-ui-<run id>`.

This is a view-only x86_64 QA build (`android.injected.build.abi=x86_64`). It does not exercise arm64 tunnel/cloudflared or prove release-native runtime behavior. The original signed release workflow separately bundles/verifies those runtimes and checks production signer continuity plus public APK download. Final evidence must reference completed CI results; a workflow definition alone is not a test pass.

## Remaining physical acceptance

- Check exact marker position at screen edges, system bars and fold/rotation boundaries; no ghost effects from old geometry.
- Press Pause/Manual/Stop/X during a gesture and while awaiting an external response. Confirm subsequent actions are blocked and no replay follows reobservation.
- Check panel fitting with IME, small landscape viewport and 200% fonts. Emergency controls must remain reachable.
- Check TalkBack focus/navigation, contrast, reduced motion, power-off removal and thermal/battery behavior over extended use.
- Actual pending Android gesture cancellation can be best-effort; pausing prevents new commands and does not undo an already accepted external action.

## Final source cross-review — PR #123 / fb2efb6

AI Android engineering/QA cross-review of the final structured presentation changes (source review, not an additional human/device acceptance):

- `VisualPhase` uses explicit events rather than guessing from a localized label. `present` and `feedback(expectedEpoch)` only update an ACTIVE session and reject an old epoch. `GenericControl` captures an operation epoch for its final result/phase feedback. A resumed/new session cannot receive an old operation's completed/failed status through those callbacks.
- Native `perform` captures `admittedEpoch` before asynchronous work, then rejects an epoch change inside the final native tree-validation block before any action. Pause/Stop interrupt the current operation; the planned cue delay is cancellable. The physical resume mutex/reobservation path is preserved.
- The 96ms dashed preview is explicitly an intended region from the consumed observation. It is not an injected point or a success marker; live nodes are reacquired afterward and action-scoped app/window/fingerprint/target checks still run. No node handle crosses the delay.
- The common gesture injector now puts its 50ms touch-pass-through settling delay inside `try/finally`. Cancellation during settling closes the pass-through token, in addition to callback/timeout cleanup; this resolves the token-leak edge case.
- `CalmThemeContrastTest` references the actual `ClosePawLightColorScheme` / `ClosePawDarkColorScheme` role definitions. An independent Python standard-library calculation on the 18 corresponding source foreground/background pairs passed WCAG normal-text 4.5:1, with the minimum **4.684:1** for light `onTertiary`/`tertiary`. This is numeric source-color evidence; it does not prove every component's composited/disabled/gradient contrast or TalkBack usability.

Review finding **resolved and source-approved**: the first security screenshot probe is preserved before showing the intended-region cue, and every shown preview is followed by its cancellable 96ms delay and a **new** `secureWindow(service)` probe before entering the final `withTree` validation. A secure-window refusal returns `ControlEffect(false, false, reason)` before mutation. The final `admittedEpoch` and full action-scoped freshness/target checks remain after this renewed verdict. This closes the added deliberate-delay stale-security-verdict issue. Android probe spacing may add waiting time; no performance improvement or latency SLA is claimed.

Final source cross-review outcome: **Pass with no remaining source blocker found in this reviewed change set**. Runtime CI, real protected-window transitions and physical overlay acceptance remain separate evidence requirements; this approval does not upgrade any Not Tested item to a device pass. `git diff --check` passed again after the correction.

## Final executed delivery evidence (2026-10-20)

PR #123 merged as96cc7013a4f00bb7e2c3c5c648360740785e33ae. PR Security39393879629/UI39393879612, main Security39394905098/UI39394905106 and Signed Release39395656700 all succeeded. Main reports:2632 JVM tests/0 failures/errors/skips; lint0 errors/119 warnings/4 info; Python55+8;6 native View UI tests and4 corrected render files. Actual normal/200% Light/Dark panel renders were reviewed; the large-font Pause truncation was fixed and guarded by no-ellipsis assertions. Original signer, manifest0.1.70/code71, source/tag, native runtimes, checksum and anonymous public APK download passed; proof artifact12252261387. [Full final evidence and acceptance matrix](premium-ai-human-ux-v0170.md). This final result supersedes initial pending-CI entries; local Gradle/pytest execution and an automated local RED were not available. Physical/human acceptance and measured device performance remain Not Tested.
