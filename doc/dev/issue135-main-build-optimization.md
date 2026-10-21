# #135 main build optimization — implementation and release evidence

## Scope and baseline

User authorized implementation, CI, signed public APK verification and final report.
Source baseline main4aa6a4a; installed/released0.1.75/code76 from04c849b7.
#126 app command latency and #128 Samsung physical overlay acceptance remain separate.

Measured v0.1.75 main→independent public APK verification653s; unsigned job604s,
security461s and UI363s in parallel, followed by signing/public overhead. R8 task347.51s,
release Kotlin68.64s/lintVital86.14s; task times overlap. PR R8 skip added by4aa6a4a
was deliberately untested until this real development. Baseline raw timestamps are
[evidence/issue132-v0175-timing.json](evidence/issue132-v0175-timing.json).

## Selected changes and rejected alternatives

| Change | Reason / expected effect | Risk / check |
|---|---|---|
| Exact-input native cache | Native unsigned29+57s and security63s rebuilt unchanged sources. Warm restore avoids Go/NDK/source setup | Immutable source/toolchain/flags/scripts key, hash/metadata/ELF, no prefix fallback, main-only single publisher; miss/corrupt builds original source |
| Remove extended icon artifact | Original extended classes.jar37,425,981 bytes/11,105 classes; actual usage53 icons | Keep core24 and package-only copies29, all original geometry/RTL/tint/cache getter code+license preserved; whole-source hash checks; no UI redesign |
| Combine test/lint/debug Gradle graph | Removes second configuration/command boundary and permits Gradle-managed overlap | All original tasks remain; task/command wall vs aggregate CPU measured separately |
| Skip normal Gradle cache cleanup | v4 supports cache-cleanup never; old post includes cleanup subprocess plus save/compress | Does not eliminate all post cost. Manual clean_gradle_cache=true is only for CI of a new unpublished version; standalone cache maintenance needs a future release-independent workflow |
| GC + aggregate Java RSS evidence | Determines whether extra heap/separate R8 JVM is justified | release-only, no env/argv dumps, sampler/profile best-effort preserve original Gradle exit code; aggregate RSS shared pages are not unique physical memory |

R8/keep/shrink checks remain enabled. No broad keep-rule deletion, fake/stale BuildConfig
metadata, SDK upgrade, paid runner, or large module reorganization. Separate R8 JVM/heap
changes are deferred unless measured GC/RSS identifies memory pressure; an arbitrary
larger heap is not a guaranteed speedup. R8-only gains eventually hit the security path:
with old other paths unchanged, about510s/8m30s is its critical-path lower bound.

Initial target: warm native9-minute range, combined6–7min research target. Predictions
are not validated results and cache/task/runner variability will be reported.

## Native trust boundary and cold/warm proof

Only unsigned main job publishes caches; security/PR consumers are read-only. Source
pins are tunnel a390c168ff1b2d14e73a95991c186c6aba3ff5a0 and cloudflared
18cdfe0a6fc7b72a0702d255a1f984e776ce0498 (dereferenced2026.10.0 tag, not tag object).
Go1.27.0, NDK27.2.12479018/API31/ARM64, CGO/flags/tool environment and action/helper/build
script hashes form the exact key. The app SHA is intentionally excluded from native
inputs; final APK metadata still binds exact main SHA and CI run.

Restored content is never executed during verification. Metadata/input digest/key,
SHA256/size, symlink limits and AArch64 executable ELF headers/segments are checked.
PR/pull_request_target/other-event saves are rejected. A corrupt immutable exact cache
falls back to a clean source build; evict/bump schema after investigation rather than
silently overwriting it. producer metadata is diagnostic; branch scope and writer
guards establish trust, not a signature attestation by the producer field.

On a fresh main cache miss (not an existing corrupt exact entry), the sole publisher
saves both entries, deletes only the
exact dedicated runner.temp/closepaw-native-cache path, then invokes require-cache-hit
restore. This proves actual remote cache roundtrip without building another APK/R8.
No same-SHA warm full-app run is used to predict ordinary changed-code releases.

## Icon provenance and validation scope

[Original BOM/source/AAR hashes and53-icon mapping](evidence/issue135-material-icons-provenance.json)
record all inputs. Core24 stay at1.7.6; extended29 source files change only package
names. Nine callsite files change imports. Original Apache copyright, license and
NOTICE are bundled in APK assets. Local upstream archive verification checks actual
original JAR/BOM/AAR SHA256, byte sizes, source identities and binary class counts.
Offline CI checks whole-file source identities and import coverage; after license
report generation it verifies the resolved core version and absence of extended.
Release assets explicitly depend on licenseReleaseReport: the previous unsigned-only
50-task graph did not regenerate the tracked dependency list. Correct current license
data takes priority over preserving a stored configuration-cache entry; plugin
incompatibility and configuration time are reported, not hidden.
Existing UI6 tests/4renders remain. Source identity proves unchanged generated vector
logic; these UI tests are not a claim of53 separate glyph pixel tests or Samsung
physical GUI acceptance.

## Validation and final evidence

Before CI: provenance/cache tests (including wrong input/schema, changed binary,
non-object manifest, symlink, bad ELF/machine, missing/corrupt fallback, main-only write),
original upstream icon archive identity, workflow/composite YAML/shell syntax and
instant release Gradle failure exit23 preservation. Exact results and CI/APK observations
are appended after publication. Final public verification still checks package,
version/code, original signer, SHA256, both runtimes, nondebug manifest and immutable
source tag. Only the final selected combination is published.

Rollback: revert the scoped cache/workflow/icon changes in a new PR/version and verify
again. Never move a released tag or replace a published APK. Existing update permissions
and pairing data are preserved. GC/metrics files contain only build resource numbers.

## Final result — v0.1.76/code77 (2026-10-22 KST)

[Issue #135](https://github.com/al-hub/closepaw/issues/135),
[PR #136](https://github.com/al-hub/closepaw/pull/136) merged once as
`f4dddbafece5acd0d6b5039950d84d830cffd21a`. This is the immutable annotated-tag/APK
source; subsequent documentation commits do not change the released binary.
Three AI reviewers covered R8/icon provenance, Gradle/native cache, and independent
pipeline/security review. No human review is claimed. One submitted implementation,
one successful PR/main cycle, no CI retry or extra full release benchmark.

| Comparable observation | v0.1.75 | v0.1.76 | Reduction |
|---|---:|---:|---:|
| R8 task |347.51s|276.87s|20.3%|
| Release Kotlin task |68.64s|59.305s|13.6%|
| Release lintVital analysis task |86.14s|68.40s|20.6%|
| Unsigned release build step, API timestamps |449s|384s|14.5%|
| Main unsigned job |604s|545s|9.8%|
| Main security/test/lint/debug job |461s|322s|30.2%|
| Main UI job |363s|359s|1.1%|
| Main CI created → public APK independently verified |653s (10:53)|593s (9:53)|9.2%|
| Final PR CI created → public verification |1461s (24:21)|1086s (18:06)|25.7%|
| Main + UI + signing runner job seconds |1471s|1267s|13.9%|
| PR + main + UI + signing runner job seconds |2707s|2002s|26.0%|

Task times overlap and must not be summed as wall time. Whole-cycle wall includes
queue and merge/observation delay. Runner seconds are summed successful job wall time,
not CPU consumption or GitHub's rounded billing. Against the earlier v0.1.74 serial
pipeline, main→public1095s→593s is45.8% shorter; whole-cycle runner2229s→2002s is10.2%
lower. Raw timestamps, all step durations, task profiles and artifact IDs are in
[machine-readable evidence](evidence/issue135-v0176-timing.json).
The new Gradle build summary is382s, GNU time command383.41s, wrapper384s and API
build step384s. The449→384 comparison uses API step boundaries on both releases.

This release includes a native cache **cold miss**: unsigned prepare106s and security
cloudflared57s. The publisher then deleted its dedicated local directory and restored
both exact remote entries with hash/input/ELF validation in3s. This proves real remote
restore, not an entire changed-code warm release. Subtracting roughly103s suggests
about8m10s with other durations unchanged, but that is an estimate and is not a measured
release or SLA. No extra APK was published merely to obtain a favorable warm number.
The6–7min research target was **not achieved**; actual main cold result is9m53s.

R8 is still the largest task:276.87s versus382s Gradle summary. The release JVM log
recorded335 pauses totaling17.862s, maximum215.399ms, **zero Full GC** across the whole
Gradle daemon. This is not an R8-only pause measurement. Four CPUs/15,982MiB RAM;
381 one-second samples found peak aggregate Java RSS7,711,388KiB (three Java processes),
minimum host available7,649,116KiB. Aggregate RSS includes shared pages. The GNU time
124,944KiB covers the Gradle client, so it is not used as R8 memory. These results do
not establish memory pressure justifying a larger heap or separate R8 JVM. Even
eliminating all observed daemon pauses removes at most17.9s of this sample; arbitrary
heap inflation is not a credible route to the6–7min target.

Release174/debug178 configuration-cache problems remain; license report tasks are
incompatible and the entry is discarded. They are reported rather than hidden.
The current release dependency-license asset was regenerated and verified. Next
experiments should isolate remaining R8 execution inputs/rules and configuration/plugin
cost with matched changed-code samples. Keep/optimizer changes require behavioral
evidence; no broad rule relaxation or heap/SDK upgrade was bundled here. Independent
cache-maintenance dispatch is also deferred because current Security dispatch can
start a release and is only documented for an unpublished version.

## Final validation and delivery

- PR [Security39683757439](https://github.com/al-hub/closepaw/actions/runs/39683757439)
  and [UI39683757480](https://github.com/al-hub/closepaw/actions/runs/39683757480) success;
  PR unsigned-release **skipped**, validating #134's main-only condition.
- Main [Security39684799317](https://github.com/al-hub/closepaw/actions/runs/39684799317),
  [UI39684799318](https://github.com/al-hub/closepaw/actions/runs/39684799318) and
  [Signed39685929310](https://github.com/al-hub/closepaw/actions/runs/39685929310) success.
  Main Android artifact12386935137:2,665 tests/0 failures/errors/skips,282 suites;
  lint0 errors/119 warnings/4 info. Python55+8, release/cache/provenance18 tests pass.
  UI6 tests and4 render artifacts retained. Both debug and release resolved-license
  checks confirm core1.7.6 and absence of extended. Local upstream archives verified;
  offline CI's upstream_archives_verified=false means no archive download, not failed
  source identity. No53-icon pixel test or Samsung GUI acceptance is claimed.
- Original-key signature, nondebug manifest/package `ai.closepaw`,0.1.76/code77,
  both AArch64 runtimes, exact trusted main CI provenance, immutable tag/source,
  anonymous public checksum/download and signer verification all passed.
  Public verification artifact12386227075. Signing workflow was unchanged.
- [Release](https://github.com/al-hub/closepaw/releases/tag/v0.1.76) /
  [signed APK](https://github.com/al-hub/closepaw/releases/download/v0.1.76/closepaw-aa-bridge.apk):
  **116,593,263 bytes**; SHA256
  `9a77f807f5360535032d97e5cf8320adf09ed5abaa11ec3ac2f9ffaef80a363f`.
  Original certificate SHA256
  `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`.
- Device: exactly one update task `4288d196-c775-435e-bc8d-db53fe0f2408` reached
  installing, then one connector timeout during replacement. After reconnect durable
  state **installed/current0.1.76/code77** and independent get_status responding
  **0.1.76/code77** confirmed completion. Remote-update/install-source permissions
  preserved; no resubmission, re-registration or data reset. Observed completion time
  includes polling/transport delay and is not Android's exact install duration.
  #126 runtime latency and #128 Samsung physical GUI acceptance remain separate OPEN
  work; installing this build does not complete those acceptance criteria.
