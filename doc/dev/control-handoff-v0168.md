# v0.1.68 / build69 — #118 and #119

Baseline: v0.1.67/code68 acceptance in #114–#117 retained. No camera or locked UI test.

## #118 diagnosis and changes

Native ACTION_CLICK acknowledgement was treated as success even with an unchanged post-observation. New result policy requires exact checked-state change for checkable targets, exact destination package for app launch, exact text for input, or observed screen change for other actions. Wider task completion is not inferred from arbitrary screen changes. Unknown results never trigger automatic tap, resend or launch retry. Single-use observations remain consumed before effect.

The launch refusal combined policy denial with a volatile `listedPackages` set: every search replaced it and dedicated-app handoffs cleared it. This is a code-proven invalidation path, not proof of the exact original Samsung Notes incident. Bounded exact selections now survive unrelated searches and dedicated handoffs. Configuration changes and explicit session transitions clear selections. Policy is still checked at launch; refusal distinguishes missing selection from blocked launch. Home remains an explicit safe navigation option; no automatic double launch.

## #119 state and wireframes

| State / wireframe | Edge / label | Top-right | Bottom-right | AI effects |
|---|---|---|---|---|
| Active / observe | Cyan; screenshot security probe, then accessibility reading | ✕ | ⏸ | Guarded, one action per observation |
| Waiting / handoff | Amber, slow breathing; user response waiting | ✕ | ✓ response complete | Blocked |
| Paused / user control | Steady border; user control / paused | ✕ | ▶ | Blocked; active job cancellation requested |
| Reobserving / resume | Current screen check | ✕ | ⏸ | Blocked until fresh guarded capture |
| Ended / complete | All windows removed | Removed | Removed | Blocked until explicit new session |

`android.control.session.start/status/wait/end` expose the session contract. Resume is never an AI operation. After explicit start, pass returned `session_id` on UI operations. Legacy clients may enter their first idle session; they cannot silently restart after stop. User pause/end increments epoch, cancels active cancellable operation, invalidates prior observations and never queues a replay. Persisted running sessions recover as ended; persisted pauses remain paused. Termux/network maintenance are outside the screen-control session.

Overlay uses a non-touchable, non-focusable full-screen decoration plus two small touchable, non-focusable windows. Controls are 48/56dp with TalkBack labels; target actions whose center falls on them are refused. Pause position moves above an observed IME. Actual screenshot security probes are labelled separately from accessibility tree reads; no images/text are persisted. Target rings show native action coordinates; scroll and result feedback use fixed metadata-only labels. Waiting animation is ~4 seconds/cycle, disabled by system animator scale or touch exploration. Screen-off/lock hides GUI and pauses; locked UI remains unsupported.

Cancel is cooperative: already-completed external work is not undone. Resume removes the touched play/confirm window during its serialized fresh capture, keeping emergency X available, so the control window cannot itself become the observation target. Resume performs a fresh security/identity capture, returns to waiting for a NEW command, and never continues/replays the interrupted command.

## Validation status

Local `git diff --check` passes. Queued UI effects also carry an admission epoch and are refused after pause/end/new-session transitions, even if the user has since resumed. Local Gradle cannot bootstrap: distribution download denied by network policy; use repository Security CI. Added JVM state-machine, checked-result, destination-package, app-selection retention and interruption/epoch tests. CI, signed release, installation and physical visual acceptance must be recorded separately after execution.

Physical acceptance: breathing border; actual pause/new-action refusal; physical play/fresh capture/old-observation rejection; X/removal/no resume; unrelated app body touch, keyboard, rotation and TalkBack. These are not proven by JVM tests. #117 retains long-run reliability, network/reboot and Gemini collection latency work.
