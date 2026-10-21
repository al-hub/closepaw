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
