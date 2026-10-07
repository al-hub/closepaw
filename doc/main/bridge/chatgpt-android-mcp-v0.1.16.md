# ChatGPT Voice ↔ ClosePaw Android MCP — v0.1.16-A

## Goal

Prove one native round trip without a laptop, relay server, FCM, or separate voice client:

```
ChatGPT Voice
  -> custom ClosePaw MCP connection
  -> Cloudflare Quick Tunnel
  -> ClosePaw Android app
  -> get_status()
  -> same ChatGPT Voice conversation
```

## Scope

The Android app contains both runtime components:

1. a loopback-only MCP HTTP ingress at `127.0.0.1:18424/mcp`
2. a Cloudflare Quick Tunnel process that exposes that loopback endpoint over temporary HTTPS

Only the read-only `get_status` tool is exposed in v0.1.16-A.

Explicitly out of scope:

- run_task / Android control
- KUM / laptop / PC
- Termux / SSH routing
- FCM / Cloud Run / relay server
- Realtime API / custom voice UI
- OAuth / stable named tunnel
- boot auto-start

## SRP boundaries

- `McpJsonRpcHandler`: MCP JSON-RPC semantics only
- `McpHttpServer`: loopback HTTP transport only
- `ClosePawStatusTool`: status data only
- `TunnelProvider`: tunnel lifecycle contract only
- `CloudflareQuickTunnelProvider`: cloudflared process only
- `ChatGptMcpService`: Android foreground-service lifecycle/orchestration only
- `ChatGptConnectionCard`: user start/stop and endpoint display only

The tunnel does not know about MCP tools. The MCP handler does not know about Cloudflare.

## Security boundary

The local MCP server binds only to `127.0.0.1`. The public tunnel currently exposes only `get_status`, which is read-only and cannot control the device. No arbitrary shell, Accessibility action, Shizuku action, or ClosePaw executor is reachable from this endpoint in v0.1.16-A.

Do not add write-capable tools until authentication and explicit approval policy are designed and tested.

## User flow

1. Update ClosePaw to v0.1.16.
2. Open Settings.
3. Under Access, press **Connect** in **ChatGPT Voice**.
4. Wait for **Connected**.
5. Copy/select the shown `https://...trycloudflare.com/mcp` endpoint.
6. Add that endpoint as the user's ClosePaw custom MCP connection in ChatGPT.
7. In ChatGPT Voice say: **“ClosePaw 상태 확인해줘.”**

Expected result: ChatGPT calls `get_status`, ClosePaw returns its actual app/version status, and ChatGPT speaks the result.

Quick Tunnel URLs are temporary and may change after reconnect/restart. Stable tunnel and authentication belong to the next phase only after this E2E succeeds.
