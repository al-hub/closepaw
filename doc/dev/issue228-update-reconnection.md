# #228 Work disposition — 2026-11-11 KST

**Partial production fix validated; original physical reconnect cause BLOCKED / #228 OPEN.**

- Candidate [Draft PR #230](https://github.com/al-hub/closepaw/pull/230), HEAD `887eb6709dfc7d6640342b1e4a871c4fedf13d51`, tree `5743d4b2ff23bcd5b827c85d6c8b79b93ff02602`. Production code remains unmerged. VERSION_NAME/CODE remain0.1.109/110; do not merge this candidate as a new release with the old published version.
- Confirmed defect: Stop used asynchronous preference apply and could be lost across immediate process replacement. Actual unchanged-code RED followed by same-fixture GREEN after synchronous durable commit on IO. Connect/restore start refusal is bounded and reported; the independent Medium finding was fixed.
- Exact-head [Security42915878283](https://github.com/al-hub/closepaw/actions/runs/42915878283) and [UI42915878299](https://github.com/al-hub/closepaw/actions/runs/42915878299) SUCCESS. Independently downloaded raw ZIP digests matched GitHub metadata and CRC passed: JVM3145 / Android34, failures0/errors0/skipped0; changed JVM classes6+3;8render PNGs. Bridge95/core8/scripts46 PASS.
- Final API35 fixture: package replacement13533ms then active local-health readiness3972ms with PID change; cold replacement2504ms then readiness4476ms; Stop replacement5001ms then no local MCP over45s. Synthetic encrypted config/marker preserved. No post-update Activity used for PASS. Recent pre-update Activity conditions do not prove every long-background/OEM case.
- Separate independent AI code/security review APPROVE for the confirmed Stop fix and investigation; unresolved Critical/High/Medium0. This is not a Codex CLI result or native GitHub review submission. Repository main was unprotected with no active rulesets; no additional native APPROVE gate was invented.
- Raw JVM artifact13809567560 SHA256 `b93f77b4497e8e125b0e428b1700b6ac84fca25ceb164e7673f65588d6038ca6`; UI artifact13810601083 SHA256 `178cfde89b684c434bae91efb87713c0c700664753f0ec4affdba23821cebcf4`. [Machine-readable evidence](evidence/issue228/work-summary.json).

## Remaining hard boundary

The v108-to-v109 phone timeout/session-termination cause remains UNCONFIRMED. Current in-memory accessibility service_connected was about31s after update submission, while the user's manual-launch report was later; these are not MCP/tunnel readiness or raw OS launch timestamps. The existing connector exposes no historical receiver/FGS/tunnel lifecycle trace sufficient to establish the old failing layer. Local HTTP /health on an AOSP x86 emulator is not native arm64 tunnel/control-plane or ChatGPT client-session acceptance.

Physical OS was directly measured as Android17/API37. CI API37 job42909197801 could not find `platforms;android-37` before emulator startup. This is an environment BLOCKED, not an OS policy refusal. Identical unsupported retries were suspended; later skipped status is never PASS.

These constraints prevent claiming the validated Stop fix is the original outage's root solution. No speculative watchdog, timeout increase, endpoint re-registration, permission bypass or diagnostic-only production APK is substituted. The next investigation needs supported same-OS reproduction and independent receiver/service/tunnel/client timing evidence. Do not manufacture a cause or use a manual post-update launch as unattended PASS.

## Delivery and device state

No version bump, production-code merge, new main build, signed Release, public-APK verification or phone install was performed. The main update containing this record is documentation only [skip ci]; it does not deploy the candidate. Published/installed baseline stays0.1.109/code110, source/tag `3e65a21137b09a8a3d2d5f64f38f567c173b2a58`, APK SHA256 `4d49dd33bb2f8a5e0307560c249db8ac6270900ac94ae9620e22cc40eebee58d`.

Baseline real Termux task597d496d-0130-434a-a65b-a87e8ed587ba completed exit0 and same-ID dedup returned identical task/job/timestamps/result. WSL task08b3e850-767d-4399-b0dc-4be1ad32f01e returned CP228_WSL_BASELINE_OK/Linux/exit0 with existing strict SSH checks. Original12,901,900-byte JSON/current SHA `3fd15e71960d447d0c4a9e9f535c26757d6632c71b68120fefb839a5ca93e438`, legacy2000/exact2000/mismatch0, unknown76 were preserved at the recorded snapshots. No retrospective pre-v107 hash is claimed.

No phone UI session or physical user action was requested in this Work, and no ended AI session was resumed. #219 remains CLOSED under its actual acceptance evidence; #229 remains outside scope.

## Detailed chronological investigation
## Current candidate — verified Stop regression, original reconnect cause still unconfirmed

The sections below are chronological investigation evidence. The initial tests-only
scope is superseded only for the confirmed Stop durability defect described here.

HEADd522ee5b/API35 UI42912531567 established a healthy pre-update connection. Actual
package replacement changed the process and restored local MCP in5807ms; a second
cold replacement restored it in4481ms. No post-update Activity launch was used for
those observations. The Stop case then FAILED: a new foreground MCP service was
started by MY_PACKAGE_REPLACED despite the prior confirmed in-memory Stop. The
source used SharedPreferences.apply(), returning before disk durability. This is a
confirmed required safety regression, not proof of the original handset outage.

The minimal production candidate commits connection intent synchronously before
acknowledging Connect/Stop, runs that disk work on IO, always stops the service even
if saving fails, and refuses restore in the current process after a failed write.
A successful explicit intent write clears that conservative guard. Failed writes
return/report failure; they are not claimed durable across a later process death.
Stop persistence transactions survive UI/service cancellation, without restarting
AI control or replaying any operation. Unit tests cover commit vs apply, failed
Stop/Connect and guard recovery. The same actual Android replacement test must now
show Stop preserved before this candidate can be reported GREEN.

The original v108-to-v109 external reconnect cause, Samsung Android17 behavior,
native tunnel/control-plane and ChatGPT client remain UNCONFIRMED/NOT_ACCEPTED.
No version increment/merge/Release/phone install is justified as the complete #228
solution from this partial Stop fix alone. SDK37 CI remains explicitly BLOCKED.

# Issue #228 — update reconnection investigation

## Current evidence (2026-11-10 KST)

The phone responds on installed 0.1.109/code110. Durable installed/current matches
independent get_status. AI control is ended/epoch0. No update or UI session started.
A fresh harmless Termux command completed exit0 with CP228_BASELINE_OK, task
597d496d-0130-434a-a65b-a87e8ed587ba. Existing legacy SHA256
3fd15e71960d447d0c4a9e9f535c26757d6632c71b68120fefb839a5ca93e438,
12,901,900 bytes, 2,000/2,000 exact, zero mismatch; SQLite total3642, terminal3566,
unknown76, active0. These are current baseline observations, not new-version acceptance.

## Cause boundary

Existing production code already handles MY_PACKAGE_REPLACED through
ChatGptConnectionReceiver -> ChatGptMcpService.restore. MainActivity.onResume
calls the same restore. No Activity-only required initialization was found in
Lead investigation and a separate independent code/security investigation.
Receiver/FGS failures have no durable historical stage evidence. The previous
Connection timed out / Session terminated observations and later manual app
launch do not identify the failing Android, tunnel, server, or client layer.

Potential exception/durability paths were found in review (config read failures
silently treated as unconfigured, second config read falling back to Quick Tunnel,
FGS rejection recorded in memory only, asynchronous connection preference writes).
They are not established as the actual v108-to-v109 cause. No speculative watchdog,
timeout increase or transport/credential change is made.

## Additional timing evidence

Current in-memory ControlDiagnostics (not loaded from disk) records the current
AgentService service_connected at1794316044664 (22:07:24.664KST), about30.928s
after the original install task creation. The original task completed at
1794316025890 (22:07:05.890KST) with result installing/current108; that task
completion was not target installation completion. The Android accessibility
process lifecycle had therefore entered early. Neither this event nor Session
terminated proves the local MCP listener or external tunnel state. The user
reported manual app execution in a22:18KST message; message times are not raw OS
launch times. Manual launch causation remains unconfirmed.

## Isolated package replacement test

Premium UX UI compiles the unchanged production app for an x86 emulator. The test
script installs only debug test artifacts, seeds synthetic encrypted configuration
and a preservation marker, terminates the background process without force-stop,
then performs a real adb install -r. It enables no Accessibility/AI control. Activity launch is now pre-update setup only; diagnostic post-FAIL rescue is recorded separately and never counts as unattended PASS. It observes local /health after enabled replacement and
absence of that listener after Stop, each for up to45 seconds. Instrumentation
checks the synthetic config/marker preservation and cleans up before existing UI
regressions. Failures retain bounded isolated OS exception evidence.

This test exercises B (Receiver/FGS/local MCP). The x86 build lacks the production
arm64 tunnel runtime: C (external tunnel) and D (ChatGPT client session) remain
NOT_TESTED. A passing enabled test on unchanged production code is a negative
reproduction for a universal Receiver defect, not a before-FAIL/after-PASS fix.
An emulator failure must be diagnosed before claiming a production cause.

No version increment, main merge, signed Release, device installation, data reset,
replay or change to #219/#229 is justified by this test alone. #228 stays OPEN until
its actual cause and new-version acceptance gates are satisfied.

## Preparation failures and matched-OS test

The first three UI attempts failed before Android lifecycle assertions, with no
production RED evidence: generic adb installation error, missing fixed APK path,
and no APKs under the assumed host output directory. Merely changing the filename
or using -t did not solve that preparation issue. The fixture now uses Gradle's
installDebug/installDebugAndroidTest tasks, then stages the exact installed base
APK reported by Android pm path for the real replacement. No production APK is used.

A confirmed Termux task3c835598-64f2-4da8-b908-c6a0d9d059f6 returned SDK37/release17,
exit0. Current physical device is Android17, superseding older Android16 notes.
The existing API35 UI gate is retained; a separate API37 replacement job runs the
same unchanged Receiver/local MCP test. Neither has native arm64 tunnel credentials,
and an AOSP emulator still does not establish Samsung OEM or client-session behavior.
Current Android FGS documentation still lists MY_PACKAGE_REPLACED as an exemption;
there is no measured OS refusal supporting an OS-policy BLOCKED conclusion yet.

## First executed lifecycle result and fixture precondition separation

HEAD51adb1dc UI42909197801 reached real APK replacement on API35. Gradle install,
Android pm path and APK staging succeeded (127361292 bytes, SHA256
2b46575907deee352a93545738995226aa3f1438758eb3ebfe8440a51f89cee1).
Replacement CLI succeeded in7494ms, but local MCP was not ready within45295ms.
This is retained as an executed negative observation on unchanged production code,
not yet the physical v108-to-v109 cause. The fresh seeded package had never been
opened and had no verified pre-update connection, unlike the physical update.

The refined fixture establishes a pre-update Activity/healthy-MCP baseline, returns
to Home, then verifies active-process replacement (including PID change), cold
replacement and Stop. No Activity launch occurs after replacement for PASS. On FAIL,
package/service/broadcast metadata is captured before any diagnostic app-launch
rescue; a rescue result never changes FAIL to PASS. Loopback health bypasses host
HTTP proxies. This separates fixture state, Receiver/service recovery and host probe.

API37 job42909197801 failed before emulator startup: SDK manager could not find
platforms;android-37. This remains BLOCKED, not an OS policy refusal or product FAIL.
The unsupported job is explicitly suspended to prevent identical automatic retries;
its skipped future status is never counted as matching-OS PASS. Existing API35 UI
and Security gates are unchanged. Actual Android17 Samsung/tunnel/client remain
separate unaccepted boundaries.

## Existing-app baseline refusal (retained, not physical reproduction)

HEAD89498ab UI42910924904 reached installed fixture setup but could not establish
local MCP even BEFORE replacement or after diagnostic Activity launch. No package
replacement was attempted in that run. Package metadata showed stopped=false and
notLaunched=false, with no MCP service record. This means the earlier seeded-fresh
negative cannot yet be assigned to update-receiver behavior.

Fixture configuration save uses the production asynchronous encrypted-preference
apply(). Instrumentation can terminate the process at test completion, so the
fixture now explicitly flushes the same real encrypted preference instance with
commit() before returning. Production save semantics are unchanged. Pre-update
launch output, bounded activity state and secret-free configuration booleans are
recorded on failure to distinguish persistence, Activity launch and service setup.
This is a test preparation correction/hypothesis, not an asserted handset cause.

## Independent production-review follow-up

The production review found one Medium issue in the async Connect path: a service
start refusal could escape its IO coroutine and terminate the app. Connect and
restore now share the same bounded start-attempt reporting boundary. A failed
request records only a fixed reason prefix and exception class, makes no automatic
retry and does not claim connection readiness. JVM tests inject permission and
background-start refusal and assert exactly one attempt, reported state and no
private exception message. Fixture cleanup also flushes encrypted settings before
its process exits so synthetic configuration cannot pollute existing UI regressions.

The Activity baseline tests a recently-used app; it does not isolate the package
broadcast exemption from every recent-foreground grace condition. AOSP API35 local
MCP evidence still is not Samsung API37/native tunnel/client acceptance.
