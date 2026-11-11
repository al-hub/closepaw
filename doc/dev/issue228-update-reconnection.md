## Required-safety audit — finite retry budget

The earlier capped-backoff tests bounded the delay, not the number of retries.
That did not satisfy the explicit no-infinite-retry condition. The controller now
allows seven retries (eight total starts), retaining the existing delay sequence.
Only an observed Connected interval of at least60 seconds resets both backoff and
budget. Brief connections do not renew the budget. Exhaustion cleans up the tunnel,
reports only tunnel_retry_exhausted, and latches the current controller so duplicate
start/restore cannot reopen its budget. A new permitted service activation obtains
a new controller; no connection-intent write, AI restart or task replay is involved.
The controller is the single failure reporter; late provider observations cannot
overwrite exhaustion or revive a stopped service.

The local MCP service remains available when the external tunnel budget is exhausted;
exhaustion is an explicit safe stop, not a reconnect PASS. A deliberate Stop/Connect
cycle can create a new service activation. There is no automatic budget-reset timer.
New regression assertions cover exact starts/retries, long idle after exhaustion,
duplicate starts, sub60-second connections and cancellation during exhaustion cleanup.
The existing60-second stable-connection recovery case verifies budget renewal.

CI results and exact reviewed source are recorded in #228, separately from the
still-unconfirmed original physical outage. This safety limit is not its asserted cure.

## Residual audit — reuse existing coverage and contain tunnel-start exceptions

The existing seven ChatGptAutoConnectTest cases already exercise retry after
process failure, duplicate start, Stop during backoff, capped backoff with network
recovery, permanent runtime absence and owner cancellation. These are existing
JVM tests, not missing tests to rebuild or physical network/OEM acceptance.

The additional fault injection targets a separate concrete boundary: a provider
can throw synchronously before publishing Failed (for example in binary/config
preparation). Contain that exception, clean up the provider, and feed only the
exception class into the existing retry/permanent-failure policy. Preserve
cancellation and recheck enabled intent before reporting failure or retrying.
The service retains the reported reason across an asynchronously delivered
Stopped observation until the next Starting transition.

Six additional JVM tests cover permanent and transient start exceptions,
permanent reason reporting, immediate stop/start cancellation, cancellation inside
a throwing start, and disabled intent. No real credential, phone mutation or
AI-control session is involved. CI outcome and exact reviewed SHA are recorded
in issue #228; adding a test is not itself a PASS claim.

The unavailable API37 emulator is not a reason to stop these independent tests,
nor can API35/JVM success replace original Samsung/tunnel/client acceptance.
No new release is justified merely to obtain diagnostic samples. The original
v108-to-v109 external reconnect cause remains unconfirmed.

## Earlier candidate — verified Stop regression, original reconnect cause still unconfirmed

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
