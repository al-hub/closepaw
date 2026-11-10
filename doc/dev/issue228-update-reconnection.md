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

## Isolated package replacement test

Premium UX UI compiles the unchanged production app for an x86 emulator. The test
script installs only debug test artifacts, seeds synthetic encrypted configuration
and a preservation marker, terminates the background process without force-stop,
then performs a real adb install -r. It never opens an Activity or enables
Accessibility/AI control. It observes local /health after enabled replacement and
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
