# ClosePaw for ChatGPT Voice — minimal MVP

## Product contract

```
ChatGPT Voice <-> ClosePaw
```

That is the entire user-visible architecture.

ChatGPT sees one tool:

```
run_task(task, target?)
```

Everything else belongs inside ClosePaw and is intentionally hidden from ChatGPT.

## What v0.1.16 proves

This MVP proves only that the native ChatGPT app can call ClosePaw and receive a
real result back in the same conversation.

Supported capability:

- ClosePaw status/connectivity test

Intentionally excluded from this MVP:

- KUM
- Termux
- SSH
- Accessibility
- Shizuku
- PC control
- Android control
- Bridge Core routing
- device selection logic beyond preserving an optional target hint

Those are added only after the ChatGPT <-> ClosePaw transport works end to end.

## External transport

ChatGPT requires an MCP connection to reach ClosePaw. For a private laptop
instance, use OpenAI Secure MCP Tunnel. Treat the tunnel as transport only; it
is not part of the ClosePaw product model.

Runtime path:

```
ChatGPT mobile Voice
  -> installed personal ClosePaw plugin
  -> Secure MCP Tunnel
  -> tools/chatgpt-plugin-mcp/server.py
  -> run_task(...)
  -> result
  -> same ChatGPT Voice conversation
```

The MCP server is stdio-only. It does not expose an inbound public port.

## Local setup

```bash
python3 -m venv .venv-closepaw-mcp
source .venv-closepaw-mcp/bin/activate
python3 -m pip install -r tools/chatgpt-plugin-mcp/requirements.txt
python3 -m pytest -q tools/chatgpt-plugin-mcp/tests
```

## Tunnel setup

After creating a tunnel in the OpenAI Platform:

```bash
export CONTROL_PLANE_API_KEY="..."
tunnel-client init \
  --sample sample_mcp_stdio_local \
  --profile closepaw \
  --tunnel-id tunnel_xxx \
  --mcp-command "python3 /ABSOLUTE/PATH/TO/closepaw/tools/chatgpt-plugin-mcp/server.py"

tunnel-client doctor --profile closepaw --explain
tunnel-client run --profile closepaw
```

## ChatGPT setup

On ChatGPT web:

1. Open Plugins.
2. Add a custom MCP server.
3. Name it **ClosePaw**.
4. Choose **Tunnel**.
5. Select/paste the ClosePaw tunnel.
6. Create and install the personal plugin.

No separate ClosePaw voice UI is created.

## First acceptance test

In the ChatGPT mobile app, start Voice and say:

> ClosePaw 상태 확인해줘.

Expected behavior:

1. ChatGPT calls `run_task`.
2. ClosePaw receives the task.
3. ClosePaw returns `status=succeeded` and
   `summary="ClosePaw is reachable and responding."`.
4. ChatGPT reports that result in the same Voice conversation.

A second useful test is:

> 내 노트북 ClosePaw에 연결 테스트해줘.

The optional `target` is preserved, but no device-routing behavior exists yet.

## Next phase — only after the MVP passes

Keep the ChatGPT surface unchanged. Extend only ClosePaw internals:

```
run_task
  -> ClosePaw internal router/executor
  -> PC / Android / KUM / other capabilities
```

The external contract remains one tool even as internal capabilities grow.
