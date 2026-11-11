# #228 Work disposition — 2026-11-11 KST

**Feasible independent regressions completed; original physical reconnect cause UNCONFIRMED / #228 OPEN.**

- [Draft PR #230](https://github.com/al-hub/closepaw/pull/230) HEAD `6570d39a0d129535fcfc299ad7e01dad03bb5df2`, tree `887a8f94b5f8b778422eab82a616f890f3c94892`. Production code UNMERGED, version0.1.109/code110 unchanged.
- Confirmed defects: Stop intent used asynchronous apply across process replacement; synchronous tunnel-provider start exceptions escaped before publishing Failed. Minimal fixes use durable IO commit, class-only reason codes, existing transient/permanent retry policy, cancellation/enabled checks and atomic error-state transforms. No watchdog, timeout increase, credential change or AI-session restart.
- Existing seven AutoConnect tests already cover duplicate starts, Stop during backoff, network return/capped delay, permanent runtime absence and owner cancellation. Reuse them. Six additional tests cover startup exceptions, permanent reasons, disabled intent, immediate restart and cancellation inside a throwing start.
- New unchanged-controller RED: HEADfe1958c419a472a6abcf68b6fb03053670274309, [Security42977857763](https://github.com/al-hub/closepaw/actions/runs/42977857763), raw3149/2 failures/0 errors/skips. Only two new startup-exception cases failed. Earlier real Stop RED remains in chronological evidence.
- Exact final [Security42979055340](https://github.com/al-hub/closepaw/actions/runs/42979055340) and [UI42979055337](https://github.com/al-hub/closepaw/actions/runs/42979055337) attempt2 SUCCESS. ZIP/SHA/CRC/raw XML independently verified: **JVM3151 / Android34, failures0/errors0/skipped0**, AutoConnect13/settings6/service-start3,8PNG. Bridge95/core8/scripts46 PASS.
- Final API35 fixture: install9920ms then active local MCP5019ms/PID changed; cold install3202ms then local MCP2217ms; Stop install6379ms then no local MCP45s. Synthetic config/marker preserved. No post-update Activity for PASS. Local x86 health is not native arm64/OEM/client acceptance.
- UI attempt1 failed at adb pull before replacement, with zero lifecycle events/JUnit0. Artifact13837355038 retained. One failed-job rerun on fresh runner passed, job130387355075; no code/gate changes for retry.
- Exact final HEAD/tree independent AI code/security APPROVE after Medium2 findings resolved; unresolved Critical/High/Medium0. Separate from Codex CLI/native GitHub review; no extra native approval gate.
- RED artifact13836517867 SHA256 `b96f04c59862fe433fc2c8fd7ca4e1780f43695b75ab2be0d049779e7ae04350`; final JVM13837675297 SHA256 `7d168a80cf155e7aaecbf6b28992cca423a862f798f8ec5b5c89b0d4aeb1924a`; final UI13836694876 SHA256 `ad6ec014eac16d0bd6b16a5cb3bc036aa6f74d1dc081f2834e03d7d8dd08ab90`. [Machine-readable evidence](evidence/issue228/work-summary.json).

## Remaining actual boundary

The original v108-to-v109 timeout/session termination layer remains unconfirmed. Retained observations cannot distinguish Android receiver/service, native tunnel/control plane, server or ChatGPT client. These new safety regressions are not proof of that historical cause. Current supported connector diagnostics expose no sufficient historical lifecycle trace or independent observation/recovery path for deliberately dropping the physical connection safely.

The API37 emulator previously failed SDK preparation. Suspended/skipped is not PASS. SDK availability is not an additional mandatory release gate by itself and does not stop independent JVM/API35 work. Same-OS reproduction is one possible investigation route; actual failing-layer evidence and physical external unattended acceptance remain required. No SDK skip or manual app launch substitutes for those results.

No formal diagnostic-sample APK, repeated install, uncertain command replay or permission bypass. A new final Release/automatic install/external unattended reconnect remain unperformed while original root-solution conditions are unmet. #228 stays OPEN.

## Delivery and preservation

Main updates for this record are docs only [skip ci]. Published/installed baseline remains0.1.109/code110, source/tag `3e65a21137b09a8a3d2d5f64f38f567c173b2a58`, APK SHA256 `4d49dd33bb2f8a5e0307560c249db8ac6270900ac94ae9620e22cc40eebee58d`. No phone UI control/physical action request, reset, token/key change, new install or ended AI-session restart.

[#219 current physical revalidation](https://github.com/al-hub/closepaw/issues/219#issuecomment-6789104140) is reused: strict SSH→WSL exit0/Linux, result/same-ID dedup, original2000 exact/current SHA/unknown76 and ended AI session preserved. This is v109 baseline evidence, not candidate acceptance. #219 CLOSED, #229 excluded.

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
