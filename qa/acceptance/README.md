# Acceptance without repeating the entire device checklist

`scripts/acceptance.py` is a read-only planner/report generator. It never operates a phone,
replays an action, starts a control session, publishes an APK, or marks a GitHub issue closed.
The catalog defines source-to-risk/test dependencies and the minimal physical checks.
Unmapped changes expand to all groups/full JVM. Security/session/cancellation regressions
are mandatory even for a narrow local selection. Existing full Security CI and Android UI
release gates remain authoritative; this first rollout does not reduce CI test coverage.

```bash
python3 scripts/acceptance.py plan --base origin/main --head HEAD --output build/acceptance/plan.json
# Run the returned jvm_selectors with Gradle --tests flags during local development.
# Release CI still executes the full JVM suite, lint/debug and existing UI suite.
python3 scripts/acceptance.py report --base origin/main --head HEAD --output build/acceptance/report.json
python3 scripts/acceptance.py performance cohorts.json --output build/acceptance/performance.json
```

Report generation rejects missing/skipped/failed test evidence and missing required classes.
CI uploads plan/report/raw host benchmark and JVM XML under the exact checkout SHA.
UI instrumentation is independently identified by the exact-SHA Premium UX UI job; a JVM
report cannot assert UI success. Retain GitHub URLs and download archival evidence before
Actions retention expires. Reports are artifacts, not writable release authorization.

Historical physical evidence keeps its original source, version, environment, input,
expected/observed result and raw log references. `REUSED_EVIDENCE` means historical context
with no dependency overlap, never a new-version PASS. Affected/unbounded dependencies or
unavailable historical diff become `NOT_TESTED`. Broad dependency sets are intentionally
conservative until there is evidence to narrow them. A new version number is not sufficient
to establish or invalidate behavior; actual source dependency changes drive reuse.

Physical lock/unlock, OEM WindowManager coordinate behavior and actual user X input are
not simulated by an XML result. For this integrated change all three remain explicit gates:
#145 unlock restore; #146 Galaxy target screen Y59; #147 Android matched local 10+10 and
physical cancellation on the changed common path. TalkBack/durability stay OPEN in #117;
other-device/foldable portability stays OPEN in #76. No historical PASS is lost.

Performance output uses nearest-rank P50/P95/P99, minimum ten trials in each arm, explicit
matched conditions and a single monotonic clock domain. Historical v0.1.79 report used a
different interpolated quantile convention; compare raw samples with the same convention.
P95/P99 with ten trials are descriptive extremes, not reliable tail estimates. Host SHA
microbenchmark speedup is not Android task speedup or Voice E2E speedup. Do not subtract
timestamps from different devices/process clocks, or attribute network/AI idle to Android.


The planner expands wildcard selectors against the checked-out top-level Kotlin JUnit4
class inventory and declared `@Test` counts. Full JVM impact requires every such class;
a single old wildcard match cannot stand in for new regression tests. Unsupported test
source conventions fail closed and require updating the inventory reader.

Performance cohort JSON requires `baseline` and `candidate`, each with immutable
`source_commit`, identical explicit `conditions` (`device`, `os`, `runtime`,
`configuration`, `workload`, `execution_path`, `network`, `scope`, `clock_domain`), and
at least ten raw `samples` containing `duration_ms`, `correct`, `calls`, `retries` and
optional `stage_ms`. Use `not_applicable` for irrelevant network/device dimensions in a
host microbenchmark. A new Android cohort must record the actual device/OS/settings/path
and network conditions, not copy host conditions. The current host benchmark emits this
schema. All durations are already measured within one monotonic domain; no cross-clock
subtraction is performed.

Local historical raw files are content-hashed and checked before reuse. Issue-comment
links are external, editable observation references, not cryptographic log attestations.
The original signer in historical records is the released APK provenance; JVM group
records explicitly say `NOT_SIGNED` and list only the expected production signer.
