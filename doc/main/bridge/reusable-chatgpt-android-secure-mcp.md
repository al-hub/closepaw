# Reusable Pattern: ChatGPT ↔ Android App via OpenAI Secure MCP Tunnel

Status: VERIFIED E2E  
Verified: 2026-10-08  
Reference implementation: ClosePaw v0.1.27

## Purpose

This document captures only the reusable core required to connect ChatGPT to an
Android app through a stable OpenAI Secure MCP Tunnel.

It intentionally excludes ClosePaw-specific product logic. The same pattern can
be reused by another Android project that exposes an MCP server on the device.

## Verified architecture

```text
ChatGPT Custom MCP
  Connection: Tunnel
  Stable tunnel_id
        │
        ▼
OpenAI Secure MCP Tunnel / Control Plane
        │ outbound HTTPS
        ▼
Android app
  bundled tunnel-client-runtime
        │
        ▼
local MCP server
  http://127.0.0.1:<port>/mcp
        │
        ▼
Android app capabilities
```

The critical property is that ChatGPT stores a stable `tunnel_id`, not a
temporary public URL. App restarts or network changes therefore do not require
editing a `trycloudflare.com` address in ChatGPT.

## 1. Android-side MCP server

Run the MCP endpoint on loopback only.

Example:

```text
http://127.0.0.1:18424/mcp
```

Minimum validation order:

1. MCP `initialize` returns HTTP 200.
2. `tools/list` exposes the expected tools.
3. A read-only status tool works locally.
4. Only then attach the tunnel.

Keep the local MCP server independent from the external tunnel transport. This
makes transport replacement and diagnosis much easier.

## 2. Bundle the official tunnel runtime

Verified upstream baseline:

- repository: `openai/tunnel-client`
- release: `v0.0.15`
- commit: `a390c168ff1b2d14e73a95991c186c6aba3ff5a0`
- Android target: `arm64`
- build mode: `CGO_ENABLED=0`

Representative build:

```text
GOOS=android
GOARCH=arm64
CGO_ENABLED=0
go build ...
```

Package the executable into the APK as an extracted native executable. Do not
hard-code tunnel credentials into the APK.

## 3. Runtime configuration

Required runtime values:

```text
CONTROL_PLANE_API_KEY=<restricted runtime key>
CONTROL_PLANE_TUNNEL_ID=tunnel_...
MCP_SERVER_URL=http://127.0.0.1:<local-port>/mcp
```

Recommended handling:

- store the runtime API key with Android Keystore-backed encrypted storage;
- keep the API key in the child process environment, not command-line arguments;
- keep the tunnel ID separately editable;
- preserve both values across normal signed app updates.

The runtime key only needs the permissions required to use/read the tunnel.
Do not embed an admin key in the app.

## 4. Android DNS compatibility issue

### Observed failure

The CGO-disabled Go runtime on Galaxy attempted to resolve:

```text
api.openai.com
```

through:

```text
[::1]:53
```

and failed with:

```text
dial tcp: lookup api.openai.com on [::1]:53:
read udp [::1]:...->[::1]:53: read: connection refused
```

Symptoms:

- `/readyz` returned 200;
- local MCP initialize returned 200;
- control-plane health stayed
  `degraded / backoff / network_error`.

### Verified workaround

Run a loopback-only HTTP CONNECT proxy inside the Android app and route only
OpenAI control-plane traffic through it:

```text
CONTROL_PLANE_HTTP_PROXY=http://127.0.0.1:18426
```

The proxy should:

- bind only to `127.0.0.1`;
- accept CONNECT only for `api.openai.com:443`;
- use Android/Java networking for DNS and the outbound socket;
- not terminate TLS;
- reject all other destinations.

Resulting route:

```text
tunnel-client
  │ CONNECT api.openai.com:443
  ▼
127.0.0.1:18426
  │ Android/Java DNS
  ▼
api.openai.com:443
```

Local MCP traffic remains direct and does not use this proxy.

## 5. Tunnel-client launch pattern

Representative runtime launch:

```text
tunnel-client-runtime run
  --health.listen-addr 127.0.0.1:18425
  --log.level=info
  --log.format=struct-text
```

Environment:

```text
CONTROL_PLANE_API_KEY=...
CONTROL_PLANE_TUNNEL_ID=tunnel_...
CONTROL_PLANE_HTTP_PROXY=http://127.0.0.1:18426
MCP_SERVER_URL=http://127.0.0.1:18424/mcp
```

Useful health endpoints for the runtime flavor:

```text
/readyz
/health/control-plane
/health/mcp
/health?details=true
```

Do not assume `/api/status` or `/api/logs` exist in the narrow runtime
flavor; they returned 404 in the verified Android build.

## 6. Capture child-process logs

Do not merely drain tunnel-client stdout/stderr.

Keep a bounded in-memory ring buffer of recent runtime log lines and redact
secret-shaped values before storage/display.

This was essential to identify the actual DNS failure.

Useful success indicators include:

```text
uses_proxy=true
route_mode=proxy
proxy_source=CONTROL_PLANE_HTTP_PROXY
mcp session initialized
tunnel metadata fetched
```

Healthy control-plane observations include:

```text
consecutive_failures=0
state=polling
```

## 7. ChatGPT-side setup

Create a Custom MCP using:

```text
Connection: Tunnel
Tunnel ID: tunnel_...
Authentication: None   # only when the local MCP design intentionally uses no extra auth
```

Do not enter the Android loopback URL into ChatGPT.

Wrong:

```text
http://127.0.0.1:18424/mcp
```

Correct model:

```text
ChatGPT knows the tunnel_id.
The Android tunnel-client knows the local MCP URL.
```

## 8. Verified E2E success criteria

The transport is considered proven only when all of these are true:

1. Android local MCP `initialize` succeeds.
2. tunnel-client `readyz` is healthy.
3. control plane has zero consecutive failures.
4. runtime log shows `tunnel metadata fetched`.
5. runtime log shows `mcp session initialized`.
6. ChatGPT can discover the MCP tools through the Tunnel connection.
7. ChatGPT calls a real tool and receives the Android app response.

Verified reference result:

```text
ChatGPT
→ OpenAI Secure MCP Tunnel
→ Galaxy tunnel-client-runtime
→ local Android MCP
→ get_status()
→ ClosePaw v0.1.27 (28)
```

## 9. Diagnostic decision tree

When ChatGPT reports that the MCP endpoint cannot be found:

### A. Local MCP initialize fails

Fix the Android MCP server first.

### B. Local MCP works, but control-plane is degraded/network_error

Inspect tunnel-client runtime logs. Do not assume Tunnel ID or MCP transport is
the problem.

### C. DNS error mentions `[::1]:53`

Use the Android loopback CONNECT proxy pattern above.

### D. `mcp-health` says `same_child_evidence_unavailable` or
`unsupported_transport`

For HTTP-streamable this may reflect health-evidence limitations rather than an
actual MCP failure. Judge E2E status from local initialize, control-plane health,
runtime logs, tool discovery, and a real tool call.

### E. Everything is healthy but ChatGPT still cannot discover tools

Check:

- exact tunnel ID;
- workspace/tunnel scope;
- Custom MCP connection type is Tunnel;
- tunnel-client is still running;
- control-plane logs show metadata fetch;
- MCP server advertises tools correctly.

## 10. Security rules worth keeping

- MCP server: loopback-only.
- Health listener: loopback-only.
- DNS CONNECT proxy: loopback-only.
- CONNECT allowlist: only required OpenAI control-plane host/port.
- Runtime key: encrypted local storage.
- Runtime key: environment variable, not argv.
- Runtime logs: redact secret-shaped values.
- Prefer a minimal read-only status tool for first E2E validation.
- Keep app capability authorization separate from transport connectivity.

## 11. Reuse checklist for another Android project

```text
[ ] local MCP server on 127.0.0.1
[ ] initialize/tools-list/status tool verified locally
[ ] tunnel-client-runtime built for android/arm64
[ ] runtime binary packaged/executable
[ ] stable OpenAI tunnel created
[ ] restricted runtime API key stored securely
[ ] CONTROL_PLANE_TUNNEL_ID configured
[ ] MCP_SERVER_URL configured
[ ] loopback health listener configured
[ ] runtime logs captured and redacted
[ ] Android DNS path tested
[ ] if needed, CONTROL_PLANE_HTTP_PROXY loopback CONNECT proxy enabled
[ ] ChatGPT Custom MCP created with Connection: Tunnel
[ ] ChatGPT tool discovery succeeds
[ ] one real tool call succeeds end-to-end
[ ] app restart recovery tested
[ ] Wi-Fi ↔ mobile-data recovery tested
[ ] device reboot recovery tested
```

## 12. What is reusable vs project-specific

Reusable:

- stable Tunnel-ID architecture;
- local MCP loopback pattern;
- tunnel-client Android packaging;
- runtime credential handling;
- Android DNS CONNECT-proxy workaround;
- health/log diagnostics;
- ChatGPT Tunnel registration;
- E2E validation sequence.

Project-specific:

- MCP tool names and schemas;
- Android app control implementation;
- Accessibility/CDP/ADB adapters;
- authorization policy for sensitive actions;
- app-specific read/write behavior.

Keep these layers separate. The tunnel is only the transport. The Android
project should remain free to evolve its own capability layer independently.
