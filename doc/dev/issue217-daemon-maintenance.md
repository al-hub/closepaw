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
- Supervisor listens only on authenticated `127.0.0.1:18424`, independently of
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
