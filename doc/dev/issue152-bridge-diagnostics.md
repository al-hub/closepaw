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

## 2026-10-25 final release and installed-device checkpoint

**Development/CI/release/installation COMPLETE; outage restoration and recovery acceptance BLOCKED.**
#152 remains OPEN. Neither CI nor diagnostic success is a successful shell command.

- PR [#160](https://github.com/al-hub/closepaw/pull/160) merged.
  APK/tag source `e454e066f6ad27b319895c061ea11d8ab2e64561`.
- PR Security [40165492845](https://github.com/al-hub/closepaw/actions/runs/40165492845) /
  UI [40165492943](https://github.com/al-hub/closepaw/actions/runs/40165492943): success.
  Main Security [40165899152](https://github.com/al-hub/closepaw/actions/runs/40165899152) /
  UI [40165899208](https://github.com/al-hub/closepaw/actions/runs/40165899208): success.
  Each exact-source raw archive independently parsed: JVM2775/UI11, failure/error/skip0, PNG8.
  Local43 release/analysis unittest PASS after incorporating #161.
- Initial RED2756/3 failures remains recorded. The first integrated candidate failed compilation
  because its schema helper belonged to AndroidOperationRuntime; d0cbaa7 defined an owned schema
  and passed final CI. Independent review found and corrected unsafe auth probing, unauthenticated
  health401 misclassification and total-call-timeout handling; final review found no remaining blocker.
- [Signed Release40166307998](https://github.com/al-hub/closepaw/actions/runs/40166307998): success.
  [v0.1.87/code88](https://github.com/al-hub/closepaw/releases/tag/v0.1.87) publishes both
  `closepaw-aa-bridge.apk` and `.sha256`. APK116654703bytes;
  SHA256 `90550ca783c00e41f3dffa91bd64b43809337e62ce7847ad3f3f28065e91f8c5`.
  Existing signer `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`;
  public apksigner v3 verification, package ai.closepaw/version/code/source all match.
  Evidence archive12588547283 SHA256272eb292c4bc5f03dba024e60c5096ee5825195e6769920d25de63bb3a3b4f65.
  Additional anonymous host APK download/hash/size/two native-runtime entries match.
  No key generation, tag move or existing-asset overwrite.
- Single install task `f468e34b-00e9-4130-8ee1-8069c8277fba`,
  request `issue152-install-v0187-20261025-once`. The first reconnect status timed out.
  After a spaced retry: durable state installed, installed/current0.1.87/code88; independent
  get_status reachable0.1.87/code88. Install request was not repeated, no physical approval needed.
- New `linux.bridge.status` works while shell is offline:
  termux_installed=true, run_command_supported=false (installed-package permission/action/exported
  service check), health_status=unavailable, authenticated_jobs_ready=false.
  process_state/supervisor_state=not_observed. recovery_attempted/command_submitted/pairing_changed=false.
  This confirms absence of the supported external service path and unavailable health, **not**
  a proved Android process kill or daemon-loss trigger.
- Fresh shell discovery: ready=false, readiness_reason=`bridge_offline_no_run_command`.
  The prior misleading termux_not_running reason no longer appears in this installed path.
  No failing shell command or arbitrary terminal UI input was submitted.
- UI session remains ended/epoch0; this Work started/resumed/ended no UI session and changed no
  accessibility settings, revision13 app tiers, token or known_hosts. No active #161 experiment was interrupted.

### Acceptance and remaining minimal action

| Condition | Final result |
| --- | --- |
| Passive installed-device diagnostics / corrected readiness reason | PASS on87/code88 |
| Official signed APK, public verification, actual installation/independent response | PASS |
| #161 integration | Preserved timing/sequence/test files; version-only overlap resolved87/code88 |
| Authenticated bridge health / harmless shell read/exit0/output | BLOCKED, NOT_TESTED |
| Existing token retained across an actual recovery | NOT_TESTED; no pairing write performed |
| Controlled daemon disappearance / unattended recovery / subsequent command | NOT_TESTED |
| Existing strict known_hosts PC SSH read/exit0/output | NOT_TESTED |
| UI-ended shell execution / user-stop no-replay after recovery | NOT_TESTED on new APK |
| Original daemon disappearance cause, whole-Termux Android lifecycle cause | UNCONFIRMED |

Minimum user action, now justified by actual installed diagnostics: open Termux and run once:

```sh
~/.termux/boot/10-closepaw-bridge
```

Do not print the token. If that hook is missing/fails, retain its non-secret error and use the
existing Settings Copy recovery command only if necessary; do not re-pair by default.

After that action, this same87 APK is sufficient for the next validation: passive authenticated
health, one harmless read with exit0/output and whitelisted recovery/boot metadata, strict existing
SSH alias/known_hosts read, then one controlled owned-daemon disappearance only after confirming
the singleton supervisor and no unrelated active jobs. Re-observe health without replaying an
unknown command, compare token file metadata, and execute one new harmless read. Preserve the
ended UI state and #161 session ownership. Stop if authentication/foreign listener/unknown outcome
requires diagnosis. Do not expand into #117 soak/reboot coverage or reopen #139.

[Machine-readable audit and selected raw main regression XML](evidence/issue152/audit.json).
