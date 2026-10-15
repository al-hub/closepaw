# Termux Local Bridge recovery — issue #88

## Incident and confirmed defects

On 2026-10-15 the device refused localhost port 18422 although the paired token and bridge script remained. Launching with `--persistent` failed on the deployed revision; `--idle-timeout-sec 0` restored authenticated execution. A later read-only device check confirmed health identity `closepaw-bridge`, protocol version 2, jobs API 1 and `kv status` exit 0. The deployed boot script used persistent idle settings but had no supervisor. Its boot log modification time predated the incident, and the current stderr file was empty. These facts do **not** establish why the original daemon disappeared; idle expiry, Android process removal and boot-hook execution remain unproven.

Code inspection found a separate reproducible recovery defect: `CLOSEPAW_BRIDGE_TOKEN=... mkdir ...; nohup python3 ...` scoped the token to `mkdir`, not Python. This could make RUN_COMMAND restart fail with `missing_or_weak_auth_token`. The fix places the environment assignment on the Python launch and reads the already persisted token, without embedding it in the command. Health also used an executing `:` probe, which falsely marked a busy daemon unavailable; authenticated GET `/v1/jobs` now checks readiness without competing for shell execution.

## Recovery behavior

- The bundled standalone Python runtime accepts `--persistent` as a compatibility alias and exposes `runtime_revision=88.1` and its idle timeout in public health.
- `--supervise` owns a private advisory lock, adopts an existing healthy/authenticated daemon, and watches every 10 seconds. A missing daemon is relaunched with idle shutdown disabled. Concurrent supervisors exit without replacing the daemon.
- Five consecutive unsuccessful launches stop recovery. Backoff is 0, 2, 4, 8 and 16 seconds; readiness has a bounded startup grace. A healthy interval resets the failure count. The supervisor never kills a foreign/unresponsive listener or repairs an authentication mismatch by changing credentials.
- Timestamped `~/.closepaw/recovery.jsonl` events retain child PID/exit code, startup, absence, authentication mismatch, incompatible protocol, port conflict and exhausted attempts. Files rotate around 64 KiB, keep one previous segment and contain no command, token or environment content. `recovery.json` contains the latest event. `SIGKILL` appears as exit code -9; that alone cannot identify Android's reason for killing it.
- Termux:Boot runs the supervisor in the foreground. Initial manual bootstrap also starts the supervisor. No healthy-daemon early exit can silently disable monitoring.
- On app runtime startup, when remote execution is enabled, one bounded maintenance attempt checks the bridge and installs the bundled supervisor over its existing authenticated execution transport. Bridge/boot payloads are compiled and deployed atomically for the daemon; the token file is neither read nor overwritten by this upgrade. The old running daemon is adopted so user jobs are not replayed. A busy bridge defers maintenance until a later session/restart request. MCP discovery also makes a bounded recovery attempt on connection refusal, with a 30-second cooldown; it never retries a user command.
- UI reports transient failures as **Offline**, preserving pairing, rather than presenting every failure as new setup. MCP readiness distinguishes unreachable daemon, response timeout and pairing mismatch. Connection refusal proves the listener is unavailable, not that a particular Android process was killed.

## Recovery limits and operator checks

A watchdog inside Termux cannot recover after Android force-stops/kills the whole Termux app and supervisor. Supported/enabled Termux:Boot can restart the hook after boot; ClosePaw does not claim that every Play Termux variant supports that add-on. RUN_COMMAND recovery requires a compatible Termux variant and permission. Android restrictions, unavailable Python, missing boot files and offline package repositories cannot be repaired by repeated pairing.

If the whole runtime is gone on a device without RUN_COMMAND, open Termux and run:

```sh
~/.termux/boot/10-closepaw-bridge
```

This reads the existing token locally; do not print/copy the token. Review `~/.closepaw/recovery.jsonl`, `~/closepaw/logs/boot.log` timestamps, Android battery/background restrictions, and whether the boot add-on actually ran. An absent boot timestamp is evidence of no recorded attempt, not proof of its cause. A failed connection gives no access to logs inside Termux from ClosePaw; report that limitation instead of inventing a remote repair channel.

## Verification scope

Python tests use real localhost daemons and supervisors for child SIGKILL recovery, duplicate launches, adoption, mismatched auth, restart/boot-hook reentry, legacy flag compatibility and port conflicts; unit tests verify bounded attempts and private rotating diagnostics. JVM tests cover exported launch credentials, no-RUN_COMMAND offline behavior, listener safety, authenticated non-executing health and paired upgrade/defer behavior.

The pre-change installed-device `kv status` check succeeded. Host process restart/reentry tests do not constitute an Android reboot or background-kill validation. New APK installation, real Android reboot and installed-device recovery acceptance must be identified separately from CI and host results.

## v0.1.45 follow-up — shell environment and retained PID recovery

The installed v0.1.44 device was verified on 2026-10-16: authenticated jobs and `kv status` worked when the caller supplied `$HOME/bin`, but a plain `kv` failed with exit 127. Its daemon inherited a minimal Android PATH and no SHELL variable. Jobs and synchronous exec both use noninteractive `bash -c`, so `.bashrc`'s `export PATH="$HOME/bin:$PATH"` is not read. This is an execution-environment defect, not a kv-specific command issue.

Revision 88.2 composes the default environment once through `default_shell_environment()` before either transport executes a command. It appends the absolute daemon HOME/bin only when missing, preserves inherited command precedence, does not modify HOME or source shell profiles, and leaves explicit caller PATH overrides unchanged. No KUM files or shell initialization files are changed.

The first real-device daemon SIGKILL test was armed only after confirming its health identity and PID; it aborts if persisted jobs are still active. Subsequent discovery remained `bridge_daemon_unreachable` beyond the recovery window. At this point the old daemon's private recovery logs were no longer remotely readable; the device's precise failure event is not claimed as confirmed yet.

A host regression reproduced a concrete gap missed by earlier tests: a supervisor adopting an existing daemon must recover even if that daemon's original parent has not reaped the killed process. `kill(pid, 0)` can succeed for the dead zombie, which previously caused `port_in_use` before bind. Now an identity-matching stale PID with no responding bridge is left unsignalled; the new server's bind decides whether the port is actually occupied, and metadata is replaced only after a successful bind. Foreign PID metadata and occupied ports still fail safely. The regression deliberately does not poll/wait the original process until a replacement is healthy.

If the old installed runtime has already exhausted recovery and Termux has no RUN_COMMAND channel, installing an APK alone cannot run a shell inside Termux. The Offline card now offers **Copy recovery command**. Paste it once in Termux: it compiles/deploys the bundled bridge and boot hook, starts the singleton supervisor, and reuses the token privately from the existing file. It does not request or overwrite the pairing token. This is a local fallback for a missing recovery channel, not a claim of fully automatic recovery after Android force-stops all Termux processes.

The pre-test Android boot ID and token file inode/size/mtime were recorded without reading the token. New APK installation, renewed real-device SIGKILL recovery, token-metadata comparison, unqualified `command -v kv` / `kv status`, and actual Android reboot remain acceptance steps until observed. The package-query check did not find a visible Termux:Boot add-on; package visibility and support must be accounted for before asserting boot capability. A host reentry test is not an Android reboot test.

The flapping regression also showed that a brief healthy response could reset the consecutive-attempt counter indefinitely. Revision 88.2 retains that backoff and adds a rolling cap of five launches per 300 seconds. Exhaustion records both the attempt count and window count; it does not clear credentials or replay jobs. The test alternates healthy/absent states and proves recovery stops after five launches.
