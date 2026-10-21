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

## 완료 결과 — 2026-10-21, v0.1.75/code76

[PR #133](https://github.com/al-hub/closepaw/pull/133), immutable APK/tag source
`04c849b701c0d6a4c8dd332053619a7135db2584`. Final PR HEAD
`f94131712cdc122a62dc52af5424269df2e073ed` passed Security39615844117/UI39615844102.
Main [Security39617725426](https://github.com/al-hub/closepaw/actions/runs/39617725426),
[UI39617725429](https://github.com/al-hub/closepaw/actions/runs/39617725429) and
[Signed Release39619258531](https://github.com/al-hub/closepaw/actions/runs/39619258531)
all completed success. Only after publication/install confirmation was this docs-only
record added; no tag was moved and no second APK was requested.

### 동일 경계의 전후 비교

| 구간 | v0.1.74 이전 | v0.1.75 이후 | 관측 변화 |
|---|---:|---:|---:|
| main CI 생성→공개 APK 독립 검증 완료 |18m15s (1095s)|10m53s (653s)|40.4% 단축,7m22s 절감 |
| 최종 PR CI 생성→공개 검증 |26m12s (1572s)|24m21s (1461s)|7.1% 단축 |
| 최종 PR CI 생성→가장 늦은 job 완료 |7m35s|8m50s|75s 증가; PR R8 검증 추가 및 queue 포함 |
| PR 마지막 job 완료→main CI 생성 |22s|4m38s|상태 관측/검토/병합 대기; 빌드로 해석하지 않음 |
| main 이후 runner job 합계(UI/서명 포함) |1465s|1471s|0.4% 증가 |
| 최종 PR+main 이후 runner 합계 |2229s|2707s|21.4% 증가; PR optimized release 추가 영향 |
| Signed Release job |640s|43s|중복 빌드를 앞선 병렬 CI로 이동; 서명 자체가93% 빨라졌다는 뜻이 아님 |

이후 main CI는14:32:42Z 생성, 공개 검증은14:43:35Z 완료했다. 두 표본 모두
같은 앱 기능·ubuntu-latest·같은 JVM/최적화·테스트 범위를 유지하되 새 PR에는
optimized release 사전 검증을 추가했다. baseline1회와 이후 PR/main2회는
동일 통제 실험/P50/P95가 아니다. 전체 CPU 사용량을 줄였다고 주장하지 않는다.
새 PR 반복 R8 비용의 후속 개선은 [#134](https://github.com/al-hub/closepaw/issues/134)에
분리했다. 일반 PR과 workflow/build/native 변경 PR의 게이트 범위를 비교하되,
main optimized release 및 모든 서명·공개 검증은 유지한다.

### 세부 계측과 남은 병목

| main 단계 | API step wall |
|---|---:|
| test+lint |274s (이전309s) |
| security cloudflared / debug APK / Gradle post |63s /29s /31s |
| unsigned OpenAI runtime / cloudflared |29s /57s |
| optimized release Gradle |449s (PR350s); 기존 직렬 test+release476s와 작업 범위가 다름 |
| unsigned APK upload / signing job download |1s /6s |
| input provenance+manifest / original signer preflight |1s /6s |
| zipalign+sign / final signer / public download+verify |3s /1s /4s |

| 정확한 task profile | main | PR |
|---|---:|---:|
| minifyReleaseWithR8 |347.51s|265.68s |
| compileReleaseKotlin |68.64s|59.071s |
| lintVitalAnalyzeRelease |86.14s|67.66s |
| lintAnalyzeDebug |176.20s|199.69s |
| lintAnalyzeDebugUnitTest |139.13s|160.47s |
| testDebugUnitTest |103.73s|112.06s |
| compileDebugUnitTestKotlin |73.33s|84.71s |
| compileDebugKotlin |71.78s|74.93s |

Task 시간은 중첩되므로 합산해 wall time으로 취급하지 않는다. R8은 release의
가장 큰 task로 확정됐지만, baseline task profile이 없어 R8 자체의 전후 가속률은
계산하지 않는다. PR/main의 차이는 runner/cache 등 조건 변동이 있는 관측이며
원인을 CPU/GC로 단정하지 않는다. PR runner는4CPU/약16GB RAM으로 계측됐다.
client `/usr/bin/time`만으로 daemon RSS/GC 병목을 진단하지 않았다.

PR/main unsigned50개 작업은41 executed/9 FROM-CACHE: Kotlin/R8 등의 캐시
미적중에도 실제 빌드 성공. configuration-cache는 unsigned-only에서 stored.
Test/lint는41개 중31 executed/10 cached이며 기존179 problems로 configuration
cache가 폐기된다. 완전히 비어 있는 SDK/의존성 캐시 실험은 별도로 실행하지 않았다.
새 binary cache는 도입하지 않았고, artifact missing/만료는 다른 소스로 대체하지
않고 main CI 재실행으로 복구한다. 실제 production artifact 변조/만료를 유발하지
않았으며 신뢰 경계 거부는4개 unittest(여러 negative subcase)로 검증했다.
이전 PR 두 파동은 새 커밋 후 cancelled가 됐고 최신 PR은 success; main/서명은
취소하지 않았다. 개발 도중 취소된 두 파동의 사용량은 위 최종 PR/배포 합계에서
제외했으며, 전체 Work 소비시간이 이 합계라고 주장하지 않는다.

### 품질·APK·설치 검증

- Android JUnit2665 tests,0 failures/errors/skips (282 XML suites), lint0 errors/
  119 warnings/4 info. Python bridge55+8, provenance4 cases 통과.
- 기존 UI6 tests와4 renders 통과. Samsung 물리 GUI 인수의 대체 증거가 아니며
  #128은 OPEN, #126 응답 지연도 별도다.
- [공개 APK](https://github.com/al-hub/closepaw/releases/download/v0.1.75/closepaw-aa-bridge.apk),
  [checksum](https://github.com/al-hub/closepaw/releases/download/v0.1.75/closepaw-aa-bridge.apk.sha256),
  [release](https://github.com/al-hub/closepaw/releases/tag/v0.1.75).
  116585012 bytes, SHA256 `ad48eb9629e8be7551643b297aaeaa1dd35b29b431aff403d08ab64dac664319`.
- Original certificate `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`;
  unsigned input provenance, nondebug manifest76, both runtimes, zipalign16KB,
  existing/new signer and anonymous public re-download checks all succeeded.
- Public evidence artifact12352979581; main test reports12354106749;
  release timings12354162781; security timings12353387671. Available from run links.
- Single update task `de99172c-2a6e-4bb1-ba3b-b66a40fece89`: installing receipt was
  followed by one connector timeout during replacement; no install resubmission.
  Then durable installed/current0.1.75/code76 and independent responding-app
  get_status0.1.75/code76 confirmed. Permissions retained; no re-registration/data reset.
- Observed submit→durable confirmation186.05s, independent status198.185s. These
  include observation intervals and a timeout; they are not exact OS install time.
  No new user approval request was needed. No #126/#128 physical checks were repeated.

[Machine-readable API timestamps and installation observations](evidence/issue132-v0175-timing.json)
allow recomputing the boundaries. Remaining cost/R8/cache/UI optimizations and limits
are documented above and in#134. The original main→public target≥30% was met in this
single sample; future runs may vary. Rollback is a new PR restoring the old workflow
and removing artifact signing, with a new version/code and full signed validation;
never overwrite0.1.75 or move its tag.
