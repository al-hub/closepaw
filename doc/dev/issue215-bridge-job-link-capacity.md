# #215 Bridge job-link capacity

Reused 2026-11-01 Galaxy evidence (0.1.102/code103): Bridge health ready,
authenticated_jobs_ready=true, RUN_COMMAND unsupported. Task
8d4f3d50-499b-4528-9dc4-6b4ecc3e63ef failed with `Job link store full; command
was not submitted.` No physical command was delivered.

## Cause and policy

TermuxJobLinkStore kept up to 1000 identity pairs forever, without cleanup.
The operation result/request ledger is separate (tasks.json, existing bound2000).
Daemon execution records and stdout/stderr are separate again. This fix changes Android link admission/result-backed link compaction and the
corresponding daemon hot-record capacity. It never deletes results, cancels jobs
or changes user/Termux files outside the existing ClosePaw job record directory.

At capacity, collect one durable task-result snapshot. Reclaim a link only when
its matching linux.shell.exec task has a matching confirmed terminal state,
retained stdout/stderr, matching daemon job ID, validated output, and explicitly
false outcome_unknown. Cancelled jobs additionally require cancel_confirmed.
No age-only expiration is safe without a retained result. Active, ambiguous,
missing-result, mismatched-job and corrupt records fail closed. Task outputs,
exit codes and request fingerprint/dedup remain available with the same task ID.
Recently completed results are retained equally; only redundant identity links
are compacted. No task retention period is shortened.

Durably reserve a slot before HTTP submission. A cross-instance JVM path lock
and OS file lock serialize current-disk reload, reclaim, reserve and bind.
An unbound `task-id\t-` means submission may be uncertain and never authorizes
replay. Bound tasks reobserve without POST. Existing two-UUID rows migrate
without rewriting them until a normal transaction. Unique staging files,
fsync and atomic replacement preserve the previous valid ledger on write failure.

No reclaimable links returns `bridge_job_link_capacity_exhausted` before POST.
An ambiguous HTTP response or bind failure retains the admission reservation;
its eventual execution outcome must be reconciled, never automatically retried.
The daemon keeps at most1000 hot records plus1000 archived records. At hot
capacity it atomically moves one confirmed terminal, non-running record into
`jobs/archive`, retaining identical bytes. Both directories load into the same
result lookup and request dedup index; archived request replay cannot execute
again. Archive capacity, no confirmed candidate, corruption, or storage failure
refuse execution explicitly. No age-only result deletion or unbounded history.
The existing task-ledger bound2000 remains unchanged. The fix does not remove
all capacity limits or repair an offline daemon.

Definite pre-execution refusal (unpaired or HTTP400/401/429) releases only an
unbound reservation; transport/invalid-response ambiguity keeps it. The durable
operation request ledger still prevents a refused request from replaying.

The new APK bundles the new daemon, but an already-running Python daemon is a
separate deployment. Existing setup/redeploy must install it before testing
new daemon archive behavior. RUN_COMMAND-unsupported devices may need one
local Termux deployment action; do not force-stop/cancel existing jobs, re-pair
or regenerate tokens to claim automatic deployment.

## Validation

Tests-first commit6c9d748: callback seam deliberately inert, full1000 fixture
expects one result-backed link to be reclaimed. Local Gradle download is blocked
by network, so raw GitHub CI reports are the execution evidence.

The same full1000 production-HTTP test against baseline closepaw_jobs.py
reproduced HTTP429 before execution. Eight stdlib unittest production-daemon
capacity cases PASS locally, including actual HTTP → bash execution exit0 /
http-capacity-ok, original bytes retained, archived results and request dedup
across restart, concurrent submit, running/unknown protection, bounded full
archive, corrupt duplicate history and archive write failure.

Green tests cover full capacity, retained output/request identity, active links,
unknown outcomes, failed proof, corruption, restart/unbound reservation,
20 concurrent admissions through separate instances (one recovered slot), and
one POST across repeated observation. HTTP integration uses the real client and
a host bash fixture, collecting exit0 and `bridge-capacity-ok` after a full ledger.
This is host integration evidence, not Android/Termux physical acceptance.

Pending exact-head CI, independent code/security review, main CI, existing-key
signed0.1.103/code104 and public cryptographic verification. Physical install and
actual Termux command remain separate acceptance until directly observed.
