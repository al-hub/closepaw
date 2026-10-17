# Tailscale recovery tools — v0.1.47 / code 48

## Goal and plan

Make Tailscale a reusable tool for an agent pursuing a private-network task,
including Termux `kv` access. This is not boot-time VPN management and is not a
`kv` exception. The caller diagnoses the original failure, chooses a target,
repairs network access when justified, then verifies the original task.

The development sequence is contract/source analysis, failing tests, independent
platform/probe/recovery/operation implementations, integration and regression
checks, main CI, signed release, then installed-phone acceptance. Existing
pairing, tunnel credentials, Termux runtime, KUM data and MCP tool schemas are
preserved.

## Operations and permissions

Use the existing discover_operations/query/execute/get_task MCP envelope.
No new MCP registration or tool definition is required.

- `network.tailscale.status` is read-only and independent of Termux. Empty input
  inspects package/control/VPN/link evidence; optional `host` and `port` probe a
  private target. A port without a host is invalid.
- `network.tailscale.ensure_connected` requires `host`; `port` defaults to 22 and
  `timeout_ms` defaults to 15000 (range 2000–20000). It requests the official
  Tailscale CONNECT_VPN broadcast at most once, then checks the private TCP target.
  The accepted broadcast is never itself a successful connection result.
- Settings → ChatGPT Voice → **Allow ChatGPT Tailscale recovery** enables mutation.
  This independent permission defaults off; existing execution permissions are
  unchanged. Read-only diagnosis remains available while control is disabled.

The tool has no disconnect, login, credential extraction, installation or shell
execution capability. It does not replay the original task. Query results and
operation descriptions tell the caller to resume only safe reads or work
confirmed not to have executed. Unknown-outcome or write operations need their
own idempotency/outcome checks.

## Responsibilities and verification

- `AndroidTailscalePlatform`: package/receiver support, explicit package-scoped
  broadcast, Android DNS/network observation and TCP connection.
- `PrivateTargetPolicy` / `PrivateTargetProbe`: bounded DNS/TCP probes; reject
  shell/URL syntax and public, loopback, link-local, mixed public/private DNS
  destinations. Private LAN subnet routes and tailnet addresses are supported.
- `TailscaleRecovery`: target-first no-op when already reachable; single recovery
  in flight; one connection request; 30-second per-runtime cooldown; bounded
  observation; permission recheck; no command executor dependency.
- `TailscaleOperations`: discovered schemas, permissions and truthful result data.
- `TailscaleDiagnostics`: last 20 metadata events, no targets, addresses, tokens,
  account information or shell text.

Permission isolation also applies when cancelling an operation: a Tailscale
control grant does not authorize cancellation of a disabled shell operation.
The permission gap was reproduced with a failing regression before correction.
Request-start/request-sent/final-result metadata is kept even if the observer
later disappears; none of those milestones claim that the VPN is connected.

TCP reachability is not SSH authentication, backend readiness, or `kv` success.
It is checked from ClosePaw's Android UID; per-app VPN routing may differ for
Termux. The agent must verify the original task in its actual execution context.
A refused TCP port is reported as a target/service problem. Unreachable TCP or
failed DNS cannot alone prove a Tailscale fault or an offline PC.

Android withholds another app's VPN owner UID. A 100.64/10 or fd7a:115c:a1e0::/48
address is link evidence, not proof of ownership or authentication. Recovery
does not replace an active VPN, including a link that resembles Tailscale. If
that VPN exists but the target fails, the tool reports uncertainty and requests
inspection of the VPN and target. This avoids overriding another VPN or
claiming that restarting Tailscale would repair a PC/SSH failure.

An absent/disabled app, unsupported receiver and declared receiver-permission
failure have separate reasons. Login expiry and VPN approval state are not
conclusively observable through this public interface. On an unconfirmed connect,
the tool reports possible user intervention rather than inventing a diagnosis.
Opening/updating/installing Tailscale or granting VPN permission can require the
user. Android/OEM background restrictions can prevent the receiver's worker
from establishing the VPN. Total device internet loss can also make the remote
MCP channel unavailable; this tool cannot be invoked without a working channel.

The agent is responsible for the diagnose → ensure → verify-original-task loop.
The device does not secretly connect Tailscale whenever any shell command fails.
ClosePaw Stop does not disconnect Tailscale.

## Source and device evidence

Upstream public IPNReceiver accepts `com.tailscale.ipn.CONNECT_VPN` and enqueues
StartVPNWorker. The worker starts only when app state and VpnService.prepare
permit; otherwise it prompts the user to open Tailscale. Source references:

- https://github.com/tailscale/tailscale-android/blob/main/android/src/main/java/com/tailscale/ipn/IPNReceiver.java
- https://github.com/tailscale/tailscale-android/blob/main/android/src/main/java/com/tailscale/ipn/StartVPNWorker.java
- https://developer.android.com/reference/android/net/NetworkCapabilities#getOwnerUid()

The pre-development Termux package query was denied cross-user permission. It
does not establish that Tailscale is missing or unsupported. Android-side queries
include package visibility and are checked by the released app itself.

New feature phone acceptance is pending APK installation: enable its local
permission; discover/query while installed and connected; disconnect Tailscale
locally without stopping ClosePaw; diagnose then ensure a known private PC TCP
target; verify `kv status` in Termux. Also inspect unsupported/user-action paths
without deleting existing app data or pairing. Unit/host evidence is not marked
as actual phone execution.
