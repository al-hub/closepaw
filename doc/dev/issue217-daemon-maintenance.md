# #217 — Independent Termux daemon maintenance

## Trust and first installation

The APK bootstrap source is delivered through the existing production-signed APK.
The initial transition requires an idle legacy daemon/supervisor to be stopped by
its local owner. The installer refuses an existing supervisor lock or live PID;
it does not kill user work, re-pair, rotate tokens or clear records. In ClosePaw's
Termux setup, use the existing token-preserving repair command once, then the
existing `~/.termux/boot/10-closepaw-bridge` hook. RUN_COMMAND is not assumed. The installer checks the existing Termux `flock` utility;
if missing it reports `missing_flock` before any source change (install util-linux locally).
A staged source or installation marker does **not** prove a running source change.
The bootstrap compares actual source bytes and backs up replaced source. An active
supervisor's lock prevents an app restart from downgrading a later approved source.

After this one-time transition, APK reinstallation is unnecessary for approved
daemon updates. The currently running maintenance engine remains the trusted
engine until a later explicit local supervisor restart; updates replace the child
daemon, not the executing monitor process.

## Approving updates

A local operator first reviews an immutable `al-hub/closepaw` commit and obtains
three SHA-256 values from an independent trusted source/review. In Termux:

```sh
python3 ~/.closepaw/bridge.py --approve-update COMMIT40 JOBS_SHA256 BRIDGE_SHA256 MAINTENANCE_SHA256
```

This explicit local action stores an owner-private approved pin and requested
revision. The supervisor notices the request on its next regular healthy loop.
Alternatively, use the registered, execution-permission-gated
`linux.bridge.maintenance.update` operation with that already-approved revision.
It cannot approve arbitrary source. There is no main/latest poll, downloaded
checksum trust bootstrap, new signing key or unsigned auto-approval. SHA values
fetched next to a download alone are insufficient authorization. The fixed repo,
40-hex commit and three independently pinned source hashes are enforced before
compilation or execution. HTTPS redirects and proxy environment are disabled.

## Runtime and safety

- Supervisor lifetime flock: one monitor. Daemon lifetime flock: one job-store
  owner even across alternate ports. Thread lock: one transaction. All command
  admission uses the same drain gate as prepare. Queued/running work, unknown
  results and corrupt history refuse replacement. Busy work is retried only on
  later regular checks; failures have a durable three-attempt target budget.
- Supervisor listens only on authenticated `127.0.0.1:18427`, independently of
  jobs on18422. Status and update use bounded schemas and response fields; tokens,
  arbitrary response bodies, user commands and outputs never become diagnostics.
- Temporary download validation, byte hashes, syntax compilation, private atomic
  write/fsync, previous-source backup and durable transaction precede replacement.
  Candidate starts with admission closed. Verification submits a fixed harmless
  command through the real JobManager and retrieves final exit0 and exact stdout
  `CLOSEPAW_MAINTENANCE_OK`; health alone is insufficient.
- Pre-activation startup/verification failure restores previous source and verifies
  its runtime using a fixed harmless command. This is `rolled_back`, not jobs
  recovery success. Token/results/dedup are untouched. The previous daemon may
  still reject jobs if its finite archive is full; no automatic history deletion.
- Before opening admission a durable activation boundary is written. After that,
  response loss or filesystem failure never triggers candidate kill/rollback:
  `activation_outcome_unknown` requires reconciliation. Interrupted transactions
  refuse further automatic replacement/restart and preserve backup/journal for
  local inspection. No user job is automatically replayed or relabeled complete.
- Fresh authenticated daemon source SHA and runtime revision are separate from
  APK version/code and historical transaction verification. Passive status never
  claims a fresh command PASS.

## 2026-11-12 — Historical unknown job and live-update admission decision

**Decision: retain the current fail-closed runtime gate.** This is a code-level
engineering disposition, **not** proof of a successful live device update.
Current physical acceptance: child-daemon recovery, fresh authenticated job and
supervisor recovery are already evidenced; independently approved *different*
daemon source activation remains BLOCKED/NOT_TESTED. Do not create a no-op
target just to satisfy this check.

### Evidence from the actual code

- `closepaw_jobs.py:JobManager._run()` launches each user command using
  `start_new_session=True` and stores its `Popen` object in the **in-memory**
  `processes` dictionary. The durable job JSON has request identity,
  fingerprint, result/state and `outcome_unknown`, **but no authenticated
  PID/PGID, process start ticks or boot-generation proof** that can exclude
  surviving command descendants after an interrupted daemon. A historic
  `interrupted` record is not an execution-completion certificate.
- `closepaw_bridge.py:Handler.maintenance_request('prepare')` checks
  `readiness()`, `has_active_jobs()`, `exec_lock` and **all**
  `outcome_unknown` jobs, then closes admission under the same admission
  lock as submission. This lock protects new submissions, but a count of zero
  currently active managed jobs does not establish that an untracked
  historical command process is gone.
- `closepaw_maintenance.py:MaintenanceController.apply()` first requires an
  operator-approved immutable 40-hex source commit and independently
  authenticated hashes for three files, a clean journal and a successful
  `prepare` before source replacement. The presence of one historic
  unknown cannot be bypassed by pin creation alone.

### Required design to *consider* distinguishing historical unknown

Only revisit the refusal if a genuine reviewed daemon change exists and a
separate change is proven necessary. A possible replacement contract must
**prove**, not infer, all of the following for the exact running daemon:

1. **Ownership / quiescence:** authenticated same-daemon provenance, single
   lifetime daemon/supervisor locks, healthy store and dedup indexes, no
   queued/running jobs, no active command group or descendant whose effects
   could overlap replacement, and a drain lock covering revalidation and
   replacement. An empty `processes` dictionary, `pgrep` absence, stale
   PID, elapsed time or `interrupted` state is not sufficient. Reused PIDs
   must never count as negative process-existence evidence. For legacy
   records without a durable provenance/boot-generation link, fail closed
   unless independently verifiable operator reconciliation exists.
2. **Unknown preserved as UNKNOWN:** never delete, migrate, overwrite,
   archive-as-success, cancel, replay or change the old job's state to gain
   admission. Retain original bytes and SHA and exact-ID / request-ID /
   fingerprint semantics across daemon changes and rollback. The original
   request always reads back its original unknown result, never executes
   again. Do not conflate this Bridge record with Android app SQLite
   `unknown` counts.
3. **Fail-closed ambiguity:** any corrupt/missing identity or index,
   orphan/descendant ambiguity, inconsistent boot/process evidence, active
   work, interrupted journal, lost response or trust mismatch refuses source
   replacement. A user-approved pin does not waive any of these checks.
   Android force-stop/boot policy remains outside this live-daemon contract.
4. **Bounded failure proof:** in isolated-home/process tests, demonstrate
   historical UNKNOWN retention and exact byte/SHA/dedup before/after a
   successful update; show explicit refusal for live or possibly orphaned
   commands, corrupt history, reused PID/boot mismatch, concurrent submit,
   activation failure and response-loss. Verify exact command exit0/stdout
   after activation, conservative rollback before activation, and no
   destructive automatic rollback after ambiguous activation.
5. **Change gates:** independent security/code review, exact-HEAD checks,
   immutable target commit and three independently pinned file SHA values,
   explicit local operator approval, controlled one-time physical device
   update, post-update authenticated command and stored-result readback.
   Neither host simulation nor a verified pin is physical update PASS.

### Current observed outcome and next trigger

On 2026-11-12 the handset was on ClosePaw v0.1.110/code111 with responding
revision217.1; existing historical Bridge job
`b65a94743f514dfe847ece9dddcd187a` remained
`interrupted/outcome_unknown=true/exit_code=null`. Authenticated readback
and physical file observation could not determine its original effects.
Approved immutable targets: **0**; requested update: absent; maintenance
transaction: absent. The existing refusal is therefore **correct** and
must not be downgraded to a bug merely to close the issue.

**Next trigger:** actual new daemon code requirement. Then assess whether
independent process/boot provenance or non-destructive operator reconciliation
is provable; if not, the on-device live update remains blocked. Document the
gate honestly and keep #217 OPEN, or explicitly separate the untested
acceptance into a later issue with user approval. No operating process,
credentials, job records, app installation or release is changed by this
decision.

## Android limitations and acceptance

Android force-stop of all Termux processes also stops this monitor. Its absence
means user action may be required, not automatic recovery success. Termux:Boot
package installed/enabled, hook presence, actual Boot activation and Android
background permission are distinct observations; the last two remain unobserved.
Installing/enabling a Boot package does not establish a successful reboot test.

Tests use isolated HOME/processes: actual old submit429 → independent source update
→ new jobs exit0/stdout; child death/restart; startup failure/rollback with exact
record bytes and dedup retained; active-job defer; unknown/corrupt refusal; duplicate
monitor; concurrency; download budget; activation response loss; interruption and
field-level redaction. Existing bounded hot/archive capacity regressions are reused.
Physical Galaxy acceptance must be recorded separately; isolated host results do
not establish a device PASS. #217 remains open while mandatory physical gates remain.
