# #152 Local Bridge offline diagnostics and recovery boundaries

## Baseline and confirmed defects

Base main `34b602a469ef3325bf13c73517c4338c7b224adf`, installed v0.1.85/code86.
[#152 existing device evidence](https://github.com/al-hub/closepaw/issues/152#issuecomment-6391961746)
is REUSED_EVIDENCE, not new APK acceptance. #88/#139 remain closed; #147 is untouched.

Confirmed by code and tests-first PR160:
- `RunCommandUnavailable` plus unavailable HTTP health emitted `TERMUX_NOT_RUNNING`.
  Package service support is not a process observation, so this does not identify the outage cause.
- A failed `restart()` could enter full bootstrap, including apt/deploy/token persistence.
  Recovery must not silently reinstall or rewrite pairing.
- Health HTTP errors and malformed responses were flattened into unavailable, allowing a response
  from an unverified listener to reach recovery. An unauthenticated health401 does not prove token
  mismatch; only identity-checked authenticated jobs401 does.

The current device daemon disappearance trigger remains **UNCONFIRMED**. No valid private
Termux process/port/boot/recovery log evidence exists while its command channel is offline.
The historical #88 unsupported-argument, environment and zombie-PID fixes are already implemented.

## Minimal change

- Separate `BRIDGE_OFFLINE_NO_RUN_COMMAND` from actual `TERMUX_START_RESTRICTED`.
- Keep authentication, foreign listener and outdated-runtime reasons ahead of recovery capability.
- Reject restart→bootstrap fallback; preserve existing token. Separate unverified HTTP responses and response timeouts; only authenticated jobs401 means token mismatch.
- Add read-only `linux.bridge.status`, gated by existing remote-execution permission, callable while
  shell readiness is false and independent of UI sessions. It checks the installed package's
  permission/action/exported-service signals, HTTP health and, only after identity/version checks, authenticated GET jobs. It neither
  invokes the recovery gate nor launches a command nor changes settings/tokens. Process and
  supervisor state remain `not_observed`; this is not a replacement for Termux-side logs.
- Reuse existing supervisor, advisory lock, bounded backoff, rolling restart cap, boot hook,
  authenticated maintenance and non-replay job transport. No duplicate daemon implementation.
- Offline Settings shows token-preserving recovery instead of new pairing for the new reason.

## Validation and acceptance

Tests-first commit `82cda75afcfbb8500d1bc90c27abc70b4ecb6ed6` intentionally adds failing
regressions for classification, restricted restart and the initial health401 hypothesis. Independent review corrected the health401
expectation: it is an unverified response, not evidence of an auth-token mismatch. Security run40151254559 completed with2756 tests/3 expected failures. Final CI must prove GREEN.
Local Gradle could not download its distribution (network unavailable); local Python has no
pytest. Do not treat these environment failures as passing tests. Local release-input unittest
suite: 39 PASS. Full Python bridge, JVM, lint/debug and required emulator tests run in GitHub CI.

Required installed v0.1.87/code88 evidence remains NOT_TESTED until observed:
passive diagnostics, authenticated harmless read/exit0, retained pairing, daemon disappearance→
supervisor recovery→read/exit0, existing known_hosts SSH read/exit0, UI-ended non-screen execution,
user-stop/no-replay. Previous lock/OFF, physical X and TCP reachability are historical reuse only.
Android whole-Termux force-stop, reboot and long soak are not silently added or marked PASS.

If offline diagnostic proves no RUN_COMMAND and no authenticated listener remains, an APK alone
cannot execute inside Termux. Minimum local action is one existing recovery hook invocation:
`~/.termux/boot/10-closepaw-bridge`. If the hook/payload is missing or exhausted, use Settings'
Copy recovery command once; never print the token or automatically enter an arbitrary UI command.
Do not request this action before the actual installed diagnostic establishes the condition.

PR160 must not auto-close #152. Keep OPEN if any required device acceptance remains missing.
Release/installation evidence will be appended after final CI, official signing and device work.

## Parallel #161 / PR162 integration checkpoint

At 2026-10-25 14:18 KST PR162 is OPEN, not merged, HEAD9c39e4e1349add04986632f7318dde1bd73e44df.
It prepares v0.1.86/code87 for device timing, rather than a test-only release-free experiment.
The sole overlapping file is gradle.properties; #152 uses candidate v0.1.87/code88 to avoid
reusing that version. Main is still34b602a4. No #161 branch, timing code, tests or device session
is modified. Recheck exact latest main and release state before merging PR160; do not advance
main during another signing run. Code may be validated independently now. Installation and
Termux recovery tests require confirming no active #161 device experiment first.
