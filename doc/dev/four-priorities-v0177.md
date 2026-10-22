# Four-priority implementation and acceptance — v0.1.77/code78

## Scope and order
User authorized implementation, TDD/SRP, original-key signed APK publication and phone installation for #139, #128, #126 and #69 on 2026-10-22 KST. #137/#138 pipeline research and #140 lock-screen UI redesign are outside this change.

1. **#139 — non-UI execution/recovery.** Preserve UI-session independence. Extract authenticated readiness/recovery from Android composition; coalesce concurrent recovery, re-probe after waiting, throttle at 30 seconds including clock=0, expose the actual recovery boundary. Only proven daemon-unreachable triggers recovery. Authentication mismatch, timeout or incompatible API never repairs pairing or replays a command. Android force-stop or absent RUN_COMMAND may require OS/user intervention; do not promise recovery without an execution path.
2. **#128 — one status island.** Remove the extra capsule painted directly into PremiumControlDecoration (not a second WindowManager island). Keep its border/target/tap effects. Route active-island physical MotionEvents ahead of Compose, use raw screen deltas after 450ms, one haptic, persist acknowledged positions; cancel multipointer gestures. Read-only status changes do not recreate the window. Clamp using measured dimensions and reject overlap with emergency X. Preserve existing X semantics.
3. **#126 — local execution and result latency.** Repair missing android.ui.sequence mutation permission and queued-session epoch gate; include sequence in bounded result waiting. The existing calculator-only fresh-observation sequence avoids repeated remote/AI gaps while preserving per-step security, X interruption, no retries and unknown-outcome refusal. No arbitrary app batching. Existing Gemini bounded overlap/footer collector retained pending direct acceptance; no shorter timeout used to manufacture a speedup.
4. **#69 — final Gemini acceptance.** Explicitly start an authorized session, launch/ask one harmless unique question, collect the new exact reply, compare its answer, confirm current installed version and end the test session. An old successful question or read-only tool advertisement does not prove the updated APK's acceptance. Speech playback and human physical dragging remain separate from machine-observed text/results.

## TDD and SRP
RED commit ad47de351b258fc5b895d78cc309e3f0761f347b on PR #141 adds recovery/gesture unit cases, sequence long-poll regression, and a decoration bitmap check rejecting the old painted capsule. CI is the Android execution environment; local Gradle wrapper distribution download is blocked and no local Android SDK is assumed. RED and GREEN results must be recorded separately after execution.

Responsibilities: TermuxJobReadinessGate owns readiness/recovery admission only; IslandDragGesture owns gesture classification only; overlay host owns native window movement/persistence; decoration owns visual effects only; runtime composes existing operation/security policies. No new production key, permissions reset or plugin re-registration.

## Baseline evidence
- Phone get_status: 0.1.76/code77 responds.
- UI session initially ended/epoch12. Without starting it, Termux read-only probe task 0eb04ae6-1a0e-4593-b25f-fd5100735cc1 succeeded, exit0, daemon duration103ms. This proves current non-UI execution, not lock/OFF state or automatic fault recovery.
- PR #130 is older unfinished work; do not merge its obsolete code over main.
- Release/install/updated-device acceptance: pending. Do not close issues until their actual gates pass.
