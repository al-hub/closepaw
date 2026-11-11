# ClosePaw #152 — Termux-local supervisor recovery ops

These are **optional, owner-authorized scripts** already exercised on the Galaxy Android 17 Google Play Termux deployment. They do **not** replace the APK, Android services, or the existing `bridge.py`, and are not installed automatically by a GitHub merge.

## Files and operational contract

- `supervisor-watchdog-job.py`: Android JobScheduler script (job **152218**, `period-ms=900000`, persisted). It is a conservative external supervisor re-entry check, **not a guaranteed 15-minute recovery SLA**. With an existing supervisor lock it does nothing unless authenticated `/v1/health`, `/v1/jobs`, and the independent `/v1/maintenance/status` attest a healthy bridge. It then rearms the three-attempt budget. An adopted child daemon may have PPID 1, so health does **not** infer failure from child PPID alone.
- `supervisor-watchdog-control.py`: explicit owner **disable** and **enable**. Disable first writes `~/.closepaw/supervisor-restart.disabled`, then cancels Job152218 and checks cancellation. Enable requires exact deployed watcher SHA, registers Job152218, verifies pending, and only then removes a matching owner stop marker. Unknown stop markers remain protected.
- Both scripts are Termux-local; user commands and unknown job outcomes are **never replayed**, and neither script modifies the Bridge token, job ledger, pairing, OS permissions, or remote SSH state.

Local commands (run inside trusted Termux, using local owner access):

```sh
"$HOME/.closepaw/supervisor-watchdog-control.py" status
"$HOME/.closepaw/supervisor-watchdog-control.py" disable
# Only after user explicitly authorizes autonomous recovery again:
"$HOME/.closepaw/supervisor-watchdog-control.py" enable
```

**Important:** `disable` pauses **future automatic supervisor re-entry**; it deliberately does not kill the running Bridge or existing user tasks. To intentionally stop the supervisor, execute `disable` **before** the separately authorized Stop action. Do not assume Android Settings **Force stop** or Android Task Manager **Stop** calls this helper: those are OS-controlled paths and cannot be intercepted by this script. Device Settings Force stop may require an explicit user launch to clear the stopped state; bypassing it is out of scope.

A successful `enable` is **scheduler registered**, not proof of a running supervisor or a passed command. If the supervisor is absent and manual restart is authorized, use the **existing validated** Termux boot hook once, then confirm maintenance HTTP200, authenticated job `exit 0`, and previous unknown job hashes unchanged. If the user requested a persistent Stop, do not run the hook.

## Safety limits

- Lock-held supervisor: no duplicate launch. Only authenticated, matching maintenance + Bridge health can reset the attempt counter to zero.
- A **shared owner-private dispatch flock** serializes periodic watchdog entry with explicit Stop/Enable. The durable Stop marker is written before waiting for an in-flight watchdog dispatch; Stop success is not acknowledged until that dispatch completes and the Android job is cancelled. This guards the Stop-versus-launch race; it does not intercept Android's own Task Manager/Settings Stop actions.
- Lock absent: at most **three** durable restart attempts; no infinite retries, no implicit reset from a scheduler ACK or launcher exit code.
- Disabled marker or unresolved maintenance transaction: fail closed.
- Local scripts must be installed as owner-private executable files, reviewed and SHA-verified; never write raw token values or source URLs to public logs.
- Android Doze/low battery can defer JobScheduler; whole-app Force stop and reboots are separate OS policy scenarios. Multi-day observation and timing statistics belong to [#117](https://github.com/al-hub/closepaw/issues/117).

## Verification

```sh
python3 -m pytest tools/termux-bridge/tests/test_supervisor_watchdog_ops.py -q
```

The host tests use a disposable `HOME`, a fake scheduler executable and fake boot hook, and never signal an operational daemon. On-device evidence for child-only SIGTERM recovery, independent supervisor recovery, bounded Stop/Enable and preserved unknown job hashes is recorded in [#152](https://github.com/al-hub/closepaw/issues/152). A separate actual one-shot test initially reported `BLOCKED_ENABLE_RESTART_UNVERIFIED` because its tester assumed the adopted child remained a direct child of the new supervisor; later process/lock/authenticated maintenance evidence established healthy adoption, and the watcher health check was corrected accordingly. That initial tester's terminal result is **not** relabeled PASS.

Do not use these scripts to broaden the scope of [#211](https://github.com/al-hub/closepaw/issues/211) (app-wide, consent-gated self-healing) or claim whole-Termux Force-stop auto-recovery.
