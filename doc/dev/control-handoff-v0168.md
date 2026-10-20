# ClosePaw v0.1.69 / build70 — #118 and #119

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

PR #120/#121, final PR CI39371629561, main CI39372119884 and Signed Release39372744855 passed. Python55+8, Android unit tests/lint, bundled native runtime, debug and signed builds succeeded. Local Gradle bootstrap was network-blocked; GitHub CI is the executed build/test evidence. Admission epochs reject queued UI work after user intervention. New state/result/selection/interruption/host-identity tests ran in CI.

Physical acceptance: breathing border; actual pause/new-action refusal; physical play/fresh capture/old-observation rejection; X/removal/no resume; unrelated app body touch, keyboard, rotation and TalkBack. These are not proven by JVM tests. #117 retains long-run reliability, network/reboot and Gemini collection latency work.


## v0.1.68 executed delivery and v0.1.69 follow-up

v0.1.68/code69 was published and automatically installed. PR #120 merged as 989de3e5bc03cbe12810cc10253812fe3312a1e1. PR CI39368968244, main CI39369410361 and Signed Release39370074414 passed; Python55+8, Android unit/lint, native/debug/signed builds and anonymous APK verification succeeded. APK116,505,245 bytes, SHA-256 7a2999171a392766a7a1483da6bc578fb72394918e06fc2ac75643b0886f4765; original signer and manifest0.1.68/code69 verified. Exactly one update task ed6867d9-ccbe-4b9a-af42-92165a5a19f5; durable status installed/current0.1.68/code69, permissions retained.

Actual device: session start17f55a67-ba89-4c33-8b8b-bf300ea16b60 and guarded current-screen observation succeeded. Discover Gemini, then Notes; launch Notes(task1d92c1b4-cb77-40a8-af36-04cd26230c08) verified exact package. Without Home or re-discovering Gemini, launch Gemini(task5f3cfdb5-30bd-43e2-ba95-08aa0977a390) reached the positively identified Google-hosted Gemini surface. No app_not_selected_or_blocked, no input/send or settings changes. Strict APK-package comparison returned target_app_not_observed/unknown. This is a conservative false negative, not success misreporting.

v0.1.69/code70 adds an optional native-attested logical application package to ControlFrame, reusing the existing complete Gemini identity/security guard. Generic result and post-observation policies match exact OS package OR this verified logical identity; generic Google Search, partial scans, secure windows and unrelated packages remain rejected. No planner-supplied alias and no launch retry. Adds positive/negative identity and zero-extra-wait tests. Follow-up CI, signed release, installation and direct Notes→Gemini result verification completed; see final delivery below.

Session end60abbd0a-4dbb-498e-901c-bb1d757214df confirmed ended; a subsequent observe refused control_session_ended/action_attempted=false. Physical pulse, pause/play/X, placement/IME/rotation/TalkBack acceptance still needs user confirmation. Previous baseline retained; no camera or locked UI test.


## Final v0.1.69 delivery and acceptance boundary

- PR #121 merged as `81451f026ed2d2748c035c97f1f6ee245423dff8`; immutable APK tag/source is v0.1.69 at that commit. Later documentation-only records do not change the APK.
- [Release](https://github.com/al-hub/closepaw/releases/tag/v0.1.69), [APK](https://github.com/al-hub/closepaw/releases/download/v0.1.69/closepaw-aa-bridge.apk). 116,508,665 bytes; SHA-256 `c67d8e8122ff4a5dbc64939f585e97e1700dab7dd17c8afbe13848d82a6393c9`. Anonymous download, original signer, manifest0.1.69/code70 and native runtimes verified by Signed Release39372744855.
- One v0.1.69 update task `dae5b18e-a05f-4e17-b56a-ae290895b700`; independent durable status installed/current0.1.69/code70. Connection and install/update permissions retained; no re-registration or reset.
- New explicit session start `a12353a5-cee4-4ed0-944d-3c5b189b19cb`; unlocked Home observation succeeded. Search Gemini, then Notes; Notes launch `2ed703ce-5b38-4d66-9408-fa82bd4efddf` verified true/known in1,300ms. Direct Notes→Gemini `247c584e-b166-40fa-9dd3-a43f0c58c995` verified true/known in2,152ms, actual OS package `com.google.android.googlequicksearchbox`, native-attested logical package `com.google.android.apps.bard`, full security scan complete. No Home detour or re-discovery. No general speedup percentage claimed.
- #118 closed after code/CI/release/device evidence. The original Wi-Fi failure is covered by checked-state regression tests; existing Wi-Fi/SSH and locked Termux evidence is reused, with no new settings, input/send or camera actions.
- #119 remains OPEN for physical acceptance: breathing border and user confirmation, pause with manual app touch and new-action refusal, physical play with fresh capture and no replay, physical X cleanup/no resume, IME/rotation/TalkBack/reduced-motion and noninterference. Do not infer these from command acknowledgement or JVM tests. #117 remains long-run stability work.
- Next step is user-assisted visual/button acceptance on the installed APK, not another baseline regression run. Resume can only be driven by the physical button; use session.status to inspect the result and acquire a new observation after resumption. Never replay an uncertain action or silently restart after a user X.
