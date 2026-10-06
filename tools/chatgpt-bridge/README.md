# ChatGPT ↔ ClosePaw Bridge Core

This module is the transport-neutral command/result/correlation layer for the
ChatGPT Voice ↔ ClosePaw closed loop.

## Responsibilities

- `models.py`: protocol parsing and result envelopes.
- `session.py`: session / command / parent-command correlation.
- `router.py`: logical device + capability routing.
- `core.py`: one dispatch boundary and normalized failures.
- `kum_adapter.py`: first PC capability; read-only KUM fast-access health probes.
- `bridge_cli.py`: JSON-lines reference transport used for local integration.

The module intentionally does **not** know about OpenAI Realtime, MCP, Termux,
SSH, Android Accessibility, or Shizuku. Those are transport/execution adapters
outside the core contract.

## Safety

The KUM adapter never executes model-provided shell text. A `capability=kum`
request is mapped to fixed read-only `systemctl --user is-enabled/is-active`
probes for `kum-fast-access.service`.

## Test

```bash
python3 -m pip install pytest
python3 -m pytest -q tools/chatgpt-bridge/tests
```

## Reference command

```json
{"protocol_version":"1.0","session_id":"sess-1","command_id":"cmd-1","parent_command_id":null,"target":{"device":"laptop","capability":"kum"},"action":"run_task","input":{"task":"Check KUM status"},"policy":{"approval":"auto_safe"}}
```

Pipe one JSON object per line into `bridge_cli.py`. The process returns one
structured result per line. A persistent gateway or OpenAI Realtime adapter can
wrap the same `BridgeCore` without changing device contracts.
