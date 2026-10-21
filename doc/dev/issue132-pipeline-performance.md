# #132: release pipeline performance

Scope: build/release wait only; #126 app latency and #128 physical GUI acceptance remain separate.
Baseline APK source c124f3f86994664c9685b883cb31e1796b38c894 (0.1.74/code75).
Implementation starts from main a867e5097d135ccef72d5e7eac476ea98e4e72e3.

## Baseline and measurement boundaries

One successful sample, not a population P50/P95. UTC times are taken from Actions API
job/step timestamps and completed-job logs. Console task lines can be buffered or
interleaved; gaps between them are **not exact task durations**.

| Boundary | Observed |
|---|---:|
| main CI creation 13:32:52 → public verification complete 13:51:07 | 1095s |
| main CI creation → release job complete 13:51:38 | 1126s |
| final PR CI creation → public verification | 1572s |
| Security CI job (39608950694 / 119252424340) | 475s |
| Unit + lint Gradle command | 309s (41 tasks:31 executed,10 cached) |
| Security cloudflared / debug APK / Gradle cache post | 63s /31s /23s |
| UI job (39608950568 /119252423897), parallel with Security | 350s |
| UI compile / emulator+test / Gradle post | 128s /158s /32s |
| Signed Release job (39610112952 /119256462036) | 640s |
| Release testDebugUnitTest + assembleRelease | 476s (81 tasks:62 executed,19 cached) |
| OpenAI native / cloudflared / Gradle post | 25s /48s /25s |
| Signing preflight / built signer check / publishing / public verification | 7s /1s /11s /5s |

Approximate total runner job usage for these three jobs:1465s; user wait1095s.
Do not add UI time to the critical path. Main CI→release handoff was seconds,
not a material baseline bottleneck. Agent review/merge wait and code editing are
outside this boundary; PR→main CI creation gap in this sample includes human/agent
and orchestration time, not just machine build time.

Baseline logs prove debug Kotlin and unit-test Kotlin compilation executed again
in release. Configuration cache was discarded with179 problems; licenseDebugReport
is incompatible. Existing build cache was active, not absent. R8 appears during
13:44:57–13:50:40 but these log boundaries cannot isolate its duration from overlapping
lint/tests; the new local Gradle profile supplies exact task timings going forward.

## Alternatives and priority

Target: ≤766.5s main CI creation→public verified APK (30% reduction), no quality reduction.
Single before/after sample can demonstrate an observation, not guarantee a service level.

| Option | Expected effect / hit vs miss | Cost/risk and validation | Decision / rollback |
|---|---|---|---|
| Parallel unsigned R8 release with tests/lint; sign exact trusted main CI artifact | Removes serial476s release Gradle stage plus native rebuild from signing; actual critical path=max(two CI jobs)+artifact/sign/public checks. Works even on cache miss; independent runners avoid R8/test CPU contention | Medium; provenance boundary is critical. Reject PR/fork/wrong workflow/SHA/run/version/hash; negative tests and API validation. Keep every test/lint/R8/native/public check | P1 implement. Revert workflow/scripts as one change; bump version for any new APK; never move published tag |
| Cancel obsolete PR CI, remove security/** push duplication | Saves obsolete job usage and speeds latest PR feedback; zero promised gain for successful main sample | Low; event-scoped concurrency, cancellation only PR. Main and Signed Release never cancelled | P1 implement; restore trigger/concurrency blocks to roll back |
| Native binary cache keyed by immutable source/Go/NDK/ABI/API/flags/script | Could save63s CI and73s former release native preparation on hit; miss no gain | Medium/high: mutable cloudflared tag, toolchain drift, cache poisoning, provenance cost | Defer until task profiles; first change removes release native duplication by artifact reuse without new binary cache |
| Reuse Gradle cache across jobs / fix configuration cache | Potential debug/test compile and configuration savings; miss no benefit | Need input identity evidence, incompatible license task and179 warnings; avoid caching keys/signing config | Defer: profiles first; current cache retained |
| Split lint/test/debug into more jobs or parallelize native preparation | Could overlap63s native with Gradle, but adds setup/compile/cache transfers and runner usage | Medium; benchmark isolated commands before expanding DAG | Defer; unnecessary to primary serial build removal |
| R8/heap/worker tuning or SDK upgrades | Unknown until task profiles/GC evidence; same release optimization required | Higher memory/compatibility risk; no proven dominant GC evidence | Defer; no R8/shrink/test disabling |
| UI emulator snapshot/path filtering | Potential158s emulator+test savings; UI already parallel | Setup/boot/actual tests need disaggregation; UI cannot simply be skipped | Retain all6 tests/4 renders; add compile/test profiles |
| Faster polling/manual dispatch | Seconds in observed handoff; agent delay separate | Little build improvement | Keep auto/scheduled path; no dummy commits |

Runner usage may increase on early failure: unsigned builds run concurrently with
cheap checks. Successful runs remove duplicate test compilation/execution and
signing-runner Gradle/Go setup; report actual usage separately. PR now validates
optimized release as well as unit/lint/debug/UI, so PR usage may increase even
while main→release latency decreases.

## Implemented trust and recovery contract

Security CI has independent security-build-test and unsigned-release jobs. Both must
succeed. Unsigned producer uses R8 + resource shrink, both production runtimes, no
signing secrets. Artifact name includes SHA; metadata binds SHA, run ID, version/code
and APK SHA256. Signing reads the exact successful Security CI run, checks repository
AND head_repository, workflow path, main branch, allowed main event, SHA and completion.
PR artifacts are never eligible, even when a PR happens to use the same SHA.
GitHub artifact transport digest verification is followed by our metadata/hash check,
nondebug manifest check and independent final public APK signature/manifest/hash check.
No artifact executable is run. Signing job has no Gradle/Go/dependency build step.

Artifact missing/expired or trust failure: stop before secrets; investigate and rerun
Security CI for the exact current main. Never substitute a PR artifact or arbitrary
latest successful run. Manual publish chooses a successful main run for current SHA,
then applies the same checks. Existing immutable-tag/incomplete-assets logic remains.
No signing material, env dumps or remote build scans in metrics/artifacts.

## Reproducible metrics

`scripts/ci-gradle.sh <label> <tasks...>` preserves original exit code, captures local
`--profile` HTML, task/cache-state log, wall time, host CPU/RAM and `/usr/bin/time -v`.
Process RSS is the Gradle client/children observation, **not total daemon/runner RSS**;
no GC/heap conclusion follows from it. Native/setup/sign/download/upload/caching/queue
use Actions per-step/job timestamps. UI profile separates compile from test command;
emulator step minus Gradle test time includes boot/install/script overhead, not boot alone.
Profiles are local artifacts retained14 days; no remote scans. Do not publish user screens
or credentials in performance reports. Installation is separately measured from single
submit to durable current/installed and independent responding-app version.

Final exact PR/main/release runs, profile task leaders, cache hit/miss, artifact overhead,
public APK checksum/signer/size, before/after reduction and installation results are
appended after successful publication. #132 stays open until evidence is recorded.
