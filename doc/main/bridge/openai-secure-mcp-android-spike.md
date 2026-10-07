# OpenAI Secure MCP Tunnel — Android Spike

Status: P1 spike  
Updated: 2026-10-07

## Why

ClosePaw currently uses a Cloudflare Quick Tunnel. The public `trycloudflare.com`
URL changes when the tunnel is recreated, which forces the direct ChatGPT
Custom MCP registration to be updated.

KUM Fast Access already avoids this operational problem by using OpenAI Secure
MCP Tunnel. This spike asks one narrow question before ClosePaw adopts the same
pattern:

> Can the official OpenAI `tunnel-client-runtime` be built and then run on Android arm64?

## Upstream baseline

Pinned upstream source:

- repository: `openai/tunnel-client`
- release: `v0.0.15`
- commit: `a390c168ff1b2d14e73a95991c186c6aba3ff5a0`
- Go: `1.27.0`
- target: `tunnel-client-runtime`, not the Cloudflare-bundled runtime

The upstream runtime accepts the inputs ClosePaw would need:

```text
CONTROL_PLANE_API_KEY
CONTROL_PLANE_TUNNEL_ID
MCP_SERVER_URL=http://127.0.0.1:18424/mcp
```

No tunnel credentials are used in the build spike.

## Spike stages

### A. Compile — current

GitHub Actions builds the upstream runtime with:

```text
GOOS=android
GOARCH=arm64
CGO_ENABLED=0
make tunnel-client-runtime
```

The workflow records `file`, `go version -m`, ELF headers, and SHA-256, then
uploads the binary as a short-lived CI artifact.

**Pass condition:** CI produces a valid arm64 ELF binary without patching
OpenAI source.

### B. Galaxy runtime — only after A passes

Bundle the built runtime in a ClosePaw test APK and run only local diagnostics
first:

1. process starts on Android
2. `--version` / `--help` works
3. health endpoint can bind to loopback
4. runtime can target `http://127.0.0.1:18424/mcp`

No production tunnel credentials should be hard-coded into the APK.

### C. Secure MCP E2E — only after B passes

Use a ClosePaw-specific tunnel identity and runtime key, stored locally with
Android-keystore-backed protection where practical.

Target architecture:

```text
ChatGPT
  ↓ stable tunnel identity
OpenAI Secure MCP Tunnel
  ↓ outbound HTTPS
ClosePaw tunnel-client-runtime
  ↓
127.0.0.1:18424/mcp
  ↓
ClosePaw MCP
```

Validate `get_status()` first, then Samsung Internet `read_app()`.

## Decision rule

- Compile fails: record why and return immediately to the existing Quick Tunnel P1 path.
- Compile passes but Android runtime fails: do not redesign ClosePaw around the tunnel.
- Android runtime and `get_status()` E2E pass: make Secure MCP Tunnel the preferred
  stable connection and keep Quick Tunnel as development/fallback.

This spike does not expand P1 into an authentication project. It only tests
whether the stable transport used successfully by KUM is technically viable
inside ClosePaw Android.
