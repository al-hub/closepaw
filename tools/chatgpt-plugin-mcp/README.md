# ClosePaw ChatGPT Plugin MCP

This is the ChatGPT-facing adapter for ClosePaw.

It is deliberately **not** a separate voice client. ChatGPT's own mobile Voice
experience remains the user interface.

## Target flow

```
ChatGPT Android/iOS app
  -> Voice / GPT-Live
  -> installed ClosePaw plugin
  -> get_kum_status MCP tool
  -> Secure MCP Tunnel
  -> local MCP server on laptop
  -> Bridge Core
  -> PC/KUM adapter
  -> structured result
  -> same ChatGPT Voice conversation
```

ChatGPT Live can use plugins available to the user's account. The plugin is
created in ChatGPT from this MCP server; the local server is kept private behind
OpenAI Secure MCP Tunnel.

## Why stdio

Secure MCP Tunnel can launch or reach an MCP server inside the laptop's trust
boundary. This server uses stdio so ClosePaw does not need to open a local HTTP
port or expose an inbound public endpoint.

## First tool

### `get_kum_status`

Read-only. It maps to the existing high-level Bridge Core capability:

- target device: `laptop`
- capability: `kum`
- action: `run_task`
- approval policy: `auto_safe`

The model cannot supply arbitrary shell source. The existing KUM adapter maps
the request to fixed read-only service probes.

## Local test

```bash
python3 -m venv .venv-closepaw-mcp
source .venv-closepaw-mcp/bin/activate
python3 -m pip install -r tools/chatgpt-plugin-mcp/requirements.txt
python3 -m pytest -q tools/chatgpt-plugin-mcp/tests
python3 tools/chatgpt-plugin-mcp/server.py
```

Running `server.py` directly starts an MCP stdio server and waits for an MCP
client. Use MCP Inspector or Secure MCP Tunnel to exercise it.

## Secure MCP Tunnel

Prerequisites are supplied by OpenAI Platform:

- a `tunnel_id`
- a runtime API key with Tunnel Use permission
- `tunnel-client`

Example profile:

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

Keep `tunnel-client run --profile closepaw` active while using the plugin.

## Create the ChatGPT plugin

Plugin creation is performed in ChatGPT on the web:

1. Open **Plugins**.
2. Select **+** -> **Add custom MCP server**.
3. Name it **ClosePaw**.
4. Under Connection choose **Tunnel**.
5. Select the ClosePaw tunnel (or paste its `tunnel_id`).
6. Review the risk warning and create it as a plugin.
7. Install the resulting personal plugin.

After installation, the same plugin can be used by ChatGPT Live on supported
mobile surfaces, subject to account/plan/workspace availability.

## E2E acceptance test

Open ChatGPT on Android, enter Voice/Live, and say:

> 내 노트북에서 KUM 상태 확인해줘.

Pass criteria:

1. Live selects the ClosePaw `get_kum_status` tool.
2. The request crosses Secure MCP Tunnel to the laptop.
3. Bridge Core routes it to the PC/KUM adapter.
4. The adapter returns the actual KUM Fast Access state.
5. The result returns to the same Voice conversation.
6. ChatGPT explains the real state aloud.
7. No separate ClosePaw voice UI, browser voice page, Termux choice, SSH choice,
   or adapter selection is exposed to the user.

## Scope of v0.1.16

v0.1.16 proves the ChatGPT-app-native read-only loop. It does not yet expose
general write/control tools. Those should be added incrementally behind explicit
risk annotations and approval policy after the read-only loop is verified.
