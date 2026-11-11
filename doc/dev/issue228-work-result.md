## v0.1.110 delivered; real unattended update accepted — 2026-11-11 KST

**Release/public integrity/install/external MCP/command/result/dedup/preservation PASS for this one real update. Original v108→v109 cause remains UNCONFIRMED; #228 stays OPEN as explicitly agreed. #219 CLOSED/#229 excluded.** This supersedes earlier deployment-blocked snapshots below.

User accepted the concrete proposal to retain the historical cause as unconfirmed and keep #228 OPEN while shipping the verified safety fixes and performing actual update acceptance ([approval record](https://github.com/al-hub/closepaw/issues/228#issuecomment-6793477419)). PR230 reviewed head12e9c2aa/tree0daeb2d9 merged to immutable source/tag `9c8eaacd6f326edb23584b5149b54997d30a3f27`. app/tools/scripts/workflows/gradle inputs match the reviewed candidate; merge adds recorded documents. No extra native GitHub review gate was introduced.

- Exact-main [Security43004499563](https://github.com/al-hub/closepaw/actions/runs/43004499563)/[UI43004499538](https://github.com/al-hub/closepaw/actions/runs/43004499538) SUCCESS. Independently re-downloaded digest/CRC/XML: JVM3154/Android34, failures/errors/skips0,8PNG. API35 active2810ms/PID change/cold1629ms after installer return; Stop45s no restart; synthetic preservation PASS. Physical Samsung acceptance below is separate.
- [Signed43005672169](https://github.com/al-hub/closepaw/actions/runs/43005672169) SUCCESS. Earlier43005642854 refused before signing while the both-CI gate was not satisfied; the later automatic completion event succeeded. No manual rerun or gate bypass.
- [Release v0.1.110](https://github.com/al-hub/closepaw/releases/tag/v0.1.110), code111, published19:41:47KST. [APK](https://github.com/al-hub/closepaw/releases/download/v0.1.110/closepaw-aa-bridge.apk)116960251B/SHA256 `3697ec7e3d93161e43fd36a5bf614309e367c4f25f1ee191400731e65fb31d9c`. Anonymous runner and independent Work download/checksum/CRC/two-native entries matched. Digest-verified public evidence confirms ai.closepaw/110-code111/v3/original signer `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba` and source/tag.
- Main JVM artifact13848565727 SHA2d70d13e…; UI13848141795 SHA4af6cec3…; public verification13848181896 SHA04136d01…. [Permanent structured result](evidence/issue228/v0.1.110-delivery-acceptance.json) records full digests, source and counts.

### Actual device update and external acceptance

After public verification, current discovery/check returned ready/available0.1.110. Independent GitHub telemetry was preflighted through existing strict SSH→WSL gh, without Microduck. A bounded detached Termux observer's first healthy sample was read from GitHub before installation. Its bootstrap task finished before the pre-install ledger baseline; no long-running admitted task was left to become unknown across the update.

Install submitted exactly once: request cp228-install-0.1.110-20261111-01/task1a11b561-a6c2-42e0-ba0d-c072d0c3da0f/session1605895705. Task returned installing at1794396342871 with old109/current; that was not installation completion. After a reconnect allowance, durable status confirmed installed/current110-code111 and independent get_status responded110-code111. No user approval/app-launch request, manual recovery report, MCP re-registration, UI action or AI-session start/resume.

Native process start was20:26:18.543891KST,52986.891ms after install submission and35672.891ms after the task's installing result. The first sampled new-runtime control-plane ok was20:26:35.340 (69783ms after submission). A timestamped external acceptance command was admitted20:27:58.553 (152996ms after submission): this is an observed external-working upper bound, not the exact first recovery time; earlier successful durable/get_status responses were not individually timestamped. All150 roughly2s samples returned local/native HTTP200; sub-sample downtime is not excluded. Native MCP semantic unknown/unsupported_transport remains distinct from these actual successful external requests.

Fresh Termux→strict SSH→WSL task837fd618-384f-476a-b907-d98264704e4b/jobc96ce259e4c842fb9c2783a05221b802 completed exit0/stdout CP228_V110_ACCEPTANCE_OK + Linux. Exact identical request after confirmed success returned the same task/job/times/result; no duplicate execution. Prior v109 result afda9642…/job244dc104… remains exit0/Linux.

Pre-install ledger3655/terminal3579→post-install3656/3580→after command+dedup3657/3581: exactly install1+command1. active0/unknown76 unchanged. Original12901900B/SHA3fd15e71…/legacy2000-exact2000/reconciled0/mismatch0 preserved; pre_upgrade_integrity=not_available unchanged. AI before/after ended/epoch0; in-memory UUID changed50ff4811…→e368f79b… on process recreation, not an identical-session-ID preservation claim. Existing authorization/SSH and update/install-source permissions worked; no private settings-byte comparison is available.

The observer completed150 samples with no failed prior publication; [GitHub observer](https://github.com/al-hub/closepaw/issues/228#issuecomment-6793518256)/[full sanitized data](evidence/issue228/update-observer-v0.1.110.json). It only reads local HTTP and writes bounded status/time evidence through existing approved GitHub access. No listener/daemon/key/token change, private app storage, ADB/admin workaround or Microduck MCP call.

### Remaining disposition

The approved release and one actual unattended update are complete. The original old failure layer is still unconfirmed; one success is not long-run reliability or proof that a particular safety fix caused the original incident. Actual OS permission-revocation/approval/OEM combinations not exercised remain untested. #228 remains OPEN under the agreed disposition; #117 owns long-run repeat scope and #229 is not accepted here. Do not repeat this installation or issue another diagnostic-only production APK.

## Unpublished v0.1.110/code111 candidate verification — 2026-11-11 KST

Candidate HEAD `12e9c2aac37f34360ea8cdb1dfffcc719f67cc8b` / tree `0daeb2d9625d5f084259d3844cafdc7b8404c332`. [Draft PR230](https://github.com/al-hub/closepaw/pull/230) is unmerged. Current phone and latest published APK remain109/code110; no new Release or installation. This section supersedes older candidate/version snapshots below. Microduck MCP is optional only if unavoidable, is not a required gate, and was called0 times in this continuation.

Compared with reviewed fb84682e, app/tools/scripts/workflows are unchanged. Prepared unpublished VERSION_NAME0.1.110/CODE111 and integrated main852a1731 investigation documents. Exact new [Security42994706594](https://github.com/al-hub/closepaw/actions/runs/42994706594)/[UI42994707077](https://github.com/al-hub/closepaw/actions/runs/42994707077) SUCCESS. SHA/CRC/raw XML: JVM3154/Android34/failures0/errors0/skipped0,8PNG. Bridge95/core8/scripts46 PASS. API35 actual replacement active2817ms/PID changed, cold4920ms after installer return; Stop45s no unexpected MCP; synthetic config/marker preserved. The intentionally skipped main-only unsigned-release and unsupported API37 job are not runtime test PASS.

- JVM artifact13843991439/990751B/SHA256 `1334cdea1cee4d259a03b41d9e1e743821ce0cab6fe3cc885aeafb9f0e948dc5`.
- UI artifact13843916827/542878B/SHA256 `cfd781de135489a33def52bb51cea1be5af87c67c4a22408b6250adfbeb348d2`.
- Exact-head/tree independent AI delta code/security APPROVE, unresolved C/H/M0; prior runtime review reused. Not CLI/native GitHub review or deployment gate waiver.
- [Current machine evidence](evidence/issue228/candidate-0.1.110-verification.json) and [native metadata](evidence/issue228/native-health-20261111.json).

New native evidence is available through supported Termux loopback reads without Microduck. Runtime started2026-11-10T13:17:23.871188787Z (22:17:23.871KST), startup probe succeeded13:17:23.970157849Z. The earlier Accessibility connection13:07:24.664Z precedes this runtime by599207.189ms. Pinned upstream components.go confirms started_at derives from processStartedAt=time.Now(). The gap does not identify a receiver/Activity trigger or exclude previous failed native instances.

Correct interpretation: /health/mcp HTTP200 returned a successful snapshot whose semantic payload is unknown/not_observed/same_child_evidence_unavailable with unsupported_transport/initialize_epoch0. Pinned upstream docs/health.md explicitly separates snapshot HTTP status, startup readiness and same-child protocol evidence. Startup probe and current external get_status success are separate observations. Prior raw HTTP200 evidence remains valid as HTTP reachability only; it is not semantic MCP child/client health PASS.428 completed/0 failures/timeouts/terminal failures applies only to the current native instance after22:17.

Physical baseline after the two new diagnostic tasks: ledger3652/terminal3576/active0/unknown76; original12,901,900B/SHA3fd15e71…/legacy2000exact/mismatch0 unchanged, pre_upgrade_integrity=not_available unchanged. Prior ended AI session and v109 command/result/dedup evidence are reused, not new-version acceptance. No app launch, AI session restart, intentional interruption, new listener/keys/tokens or install request.

**Original cause remains UNCONFIRMED.** Original root-solution deployment prerequisite is still unmet. Candidate CI/review/version readiness does not independently authorize changing that condition; merge/exact-main CI/original-key/public APK/new-version one-install/external unattended acceptance remain unperformed. #228 OPEN/#219 CLOSED/#229 excluded. The remaining decision is whether to retain this prerequisite pending historical/reproduction evidence, or explicitly permit the verified safety candidate to ship with the original cause unconfirmed and use the actual update for acceptance. No diagnostic-only production APK was issued.

## Observation-path preflight — 2026-11-11 18:11 KST

The runtime candidate and its exact HEAD/tree remain unchanged. Security42984443260 and UI42984443176 were rechecked as completed/success for fb84682e; their raw artifacts and separate exact-tree AI code/security approval are reused. No repeated CI/review, version change, merge, Release, install, phone UI action or AI session start was performed.

Current installed/durable version is0.1.109/code110; updater.check is up_to_date. Passive self status reports Accessibility connected and AI ended. Termux confirmed local MCP /health and native /readyz, /health/control-plane, /health/mcp all HTTP200. These are current healthy-state observations.

A new limited independent observation route was exercised successfully: a bounded Termux task sampled those four health endpoints three times, sent only timestamps/status/latency through the existing BatchMode/StrictHostKeyChecking SSH laptop connection to a WSL temporary file, then Microduck independently read that file.826bytes, SHA256 `3d77845794c531f66f19ebc0a9e5248676c57e5d3a65524eddece67f9008c4a5`. The task completed exit0 and a confirmed identical-ID request returned the same task/job/times/result; sink SHA was unchanged. No new listener, key, token, pairing or security setting was created.

This improves the previous blanket statement that no independent observation route existed: **independent retrieval of pushed healthy-state telemetry is now proven**. It does not prove observer survival during package/Android/Termux death, capture receiver/FGS or process replacement, or provide supported independent app recovery/client-session history. Termux sshd was not accepting8022, WSL had no user SSH config/forward, and no SSH service was started. No deliberate outage was introduced.

Original failure remains UNCONFIRMED. Current operation schemas expose point-in-time self state and installed/current but no historical receiver/service/tunnel transitions or client reset trace. The bounded control diagnostic ring now has64 UI events and no service_connected entry; this absence does not negate the earlier historical event. Termux bridge.err is empty and boot.log last changed before the original replacement interval. Cross-app private storage, ADB/admin and unsupported shell recovery were not attempted. Current HTTP200 cannot recover those missing historical facts, and no new production fault was observed to justify another speculative fix.

Preservation before/after: total3645→3650 and terminal3569→3574, matching exactly five diagnostic tasks; active0 and unknown76 unchanged. Original12,901,900bytes/SHA3fd15e71…/legacy2000-exact2000/mismatch0 unchanged. Old #219 result afda9642… remains exit0/Linux. AI session50ff4811… remains ended/epoch0. pre_upgrade_integrity=not_available remains unchanged.

Root-solution prerequisites remain unmet, so candidate version109/code110 was not reused for new code, and merge/main CI/Release/one-install/new-version external unattended acceptance remain BLOCKED. #228 OPEN; #219 CLOSED; #229 excluded. This is a tool/data boundary, not a request for repeated permission or an additional native GitHub approval gate. [Machine evidence](evidence/issue228/observation-preflight-20261111.json) includes task IDs, exact timings and samples.

# #228 Required-acceptance inspection — 2026-11-11 KST

**Source/automated safety inspection PASS; full #228 root-solution/physical acceptance BLOCKED. Keep OPEN.**

- Candidate [Draft PR #230](https://github.com/al-hub/closepaw/pull/230) HEAD `fb84682e9d37825e4944d98cc228d8231ac6744e`, tree `2d934588300cf681f4b399f84f5c68c27b351349`. Code unmerged; published/installed baseline remains0.1.109/code110.
- Mandatory inspection found a real gap in the prior verdict: capped backoff limited delay to60s but did not limit total attempts. Added unchanged-controller RED3152/1 failure (expected8 starts, observed9), then finite7-retry/8-start policy. Only >=60s observed Connected resets the budget; short connections do not. Exhaustion cleans up/reports tunnel_retry_exhausted and latches the controller against duplicate start. No automatic reset timer or intent/AI/task mutation.
- Controller owns failure reporting. Atomic active/terminal checks prevent late provider events from erasing exhaustion or reviving a stopped service. Independent exact-tree code/security APPROVE after two additional Medium findings resolved, unresolved C/H/M0; not CLI/native review.
- Exact final [Security42984443260](https://github.com/al-hub/closepaw/actions/runs/42984443260) / [UI42984443176](https://github.com/al-hub/closepaw/actions/runs/42984443176) SUCCESS. Independently downloaded ZIP/SHA/CRC/raw XML: **JVM3154 / Android34, failures0/errors0/skipped0**, AutoConnect16/settings6/service-start3,8PNG. Bridge95/core8/scripts46 PASS.
- Separate API35 actual replacement fixture PASS: installer3050ms then active local readiness2170ms/PID changed; cold installer3601ms then readiness3789ms; Stop installer2325ms then no local MCP45s; synthetic config/marker preserved. No post-update Activity for PASS. Local x86 health is not external native/OEM/client acceptance.
- New RED [Security42983089499](https://github.com/al-hub/closepaw/actions/runs/42983089499) artifact13838517330/921222B/SHA256 `7ad5ef67a722930e8e09e5a50e32c7dc1fe68986f8988a8cfbfb1f1acd71304a`, CRC/372 XML, only repeatedTransientFailuresExhaustBudgetWithoutAnotherAutomaticStart failed. Same-source UI42983089176 SUCCESS.
- Final JVM artifact13838619850/992775B/SHA256 `03e095e74befade1ac4f0a08bfc3043bedf3ca8de6f83cad3775427b698a6b97`; final UI13838788716/560441B/SHA256 `1783252f682fa36a0e8e58c5f36239fa740712dab0a27b2d92e934e51f5b4d00`. [Machine-readable evidence](evidence/issue228/work-summary.json) retains earlier Stop/start-exception RED/GREEN snapshots.

## Required item disposition

| Requirement | Result | Scope / remaining limit |
|---|---|---|
| Confirmed Stop/start-exception/finite-retry RED-to-GREEN | PASS | deterministic fault injection and unchanged-controller RED; not original physical root |
| Intent/Stop/inactive/permission-start failure guards | PASS | source and JVM injection; real package replacement Stop on API35 |
| Finite retries/duplicates/cancellation/stable reset | PASS | seven retries/eight starts, exhaustion latch, 59999ms refusal, >=60000ms reset, cancellation cleanup |
| Exact-head CI and raw artifacts | PASS | JVM3154/Android34; failures/errors/skipped0; SHA/CRC/XML/head matched |
| Independent code/security | PASS | exact HEAD/tree separate AI approval; not CLI/native review |
| Existing phone preservation and command/dedup | PASS | v109 baseline reused; not candidate post-update acceptance |
| Original physical failing layer and cause | BLOCKED | insufficient historical lifecycle/tunnel/client trace |
| Actual OS approval/revocation/network/replacement combinations | BLOCKED | actual new-package/OEM combinations not observed; injection is not physical proof |
| New version/code, main Security/UI, original-key/public APK | BLOCKED | unperformed pending root-solution conditions; current published version/code cannot be reused |
| New-release one-install/external unattended MCP/command/dedup/AI preservation | BLOCKED | no new signed Release or installation; current connectivity does not prove unattended update recovery |

The test envelope is explicit: permission/start failures and approval state are JVM policy/fault injections, not actual new-package OS dialogs or revoked-permission lifecycle acceptance. Existing phone command/ledger/dedup evidence belongs to v109. No current read success is converted into post-update unattended PASS.

## Actual delivery gate and tool boundary

Original v108-to-v109 Android/native tunnel/server/client failing layer is still unconfirmed. Retained connector observations expose no sufficient historical lifecycle trace or supported independent fault-observation/recovery route. These confirmed safety defects are not retrospectively asserted as that cause.

Same-OS emulator reproduction is one possible investigation route. API37 SDK preparation was unavailable and the suspended job is not PASS; emulator availability itself is not an additional global release gate. Reuse already successful network/dedup/storage evidence within its original scope. No extra native GitHub approval is required: main protected=false/rulesets=[] verified during this inspection.

Current candidate VERSION_NAME0.1.109/VERSION_CODE110 is already published. Before a final delivery, use an unpublished increased version/code and exact-main Security/UI success, existing-key signing and public verification. Those steps and new physical one-install/external unattended acceptance remain unperformed while original root-solution conditions are unmet. No speculative watchdog, duplicate install, unsupported privileged route or diagnostic-only formal Release is substituted.

Current updater.check reports up_to_date; independent get_status109/110. No new install, physical user action, AI restart, reset or key/token change. [#219 current physical evidence](https://github.com/al-hub/closepaw/issues/219#issuecomment-6789104140) is reused and #219 stays CLOSED; #229 excluded. Main edits for this record are docs only [skip ci], not deployed candidate code.

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
