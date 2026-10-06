# ChatGPT Voice ↔ ClosePaw Bridge

Status: Proposed architecture baseline  
Date: 2026-10-06

## Goal

Build a closed loop:

ChatGPT Voice -> intent interpretation -> structured ClosePaw command -> phone/PC execution -> structured result -> ChatGPT judgment -> optional next command -> spoken explanation.

The user-facing experience must not expose Termux, SSH, adapters, or transport details.

Example:

> "Check KUM status on my laptop."

ClosePaw selects the required device and execution path automatically. ChatGPT receives the result, reasons over it, and either explains the result or issues a follow-up command.

## Architecture principle

Keep the ClosePaw bridge contract independent from ChatGPT transport.

The bridge is split into:

1. **Bridge Core** — stable command/result/session contract.
2. **Device Agents** — Android ClosePaw and PC agent adapters.
3. **Transport Adapter** — Realtime API function tool now; ChatGPT plugin/MCP later when account capabilities allow it.
4. **Orchestrator** — the conversational model decides whether another high-level action is required.

Do not couple device execution to a specific OpenAI surface.

## Why this shape

As of 2026-10-06, OpenAI Realtime supports tool calls and tool results inside a live voice session, making it suitable for an end-to-end prototype of the closed loop.

Direct private write-capable custom MCP use from normal ChatGPT is still plan/capability dependent. Therefore the bridge core must be usable from both Realtime API and future native ChatGPT plugin/MCP adapters.

## Command channel

Use high-level commands. Do not expose raw tap/swipe primitives to ChatGPT unless explicitly needed for diagnosis.

Recommended initial tools:

- `run_task` — delegate a natural-language task to a target device.
- `get_task_status` — obtain progress for a long-running task.
- `cancel_task` — cancel a running task.
- `list_devices` — expose available logical devices and capabilities.
- `get_device_status` — health/capability check.

Example command envelope:

```json
{
  "protocol_version": "1.0",
  "session_id": "sess_...",
  "command_id": "cmd_...",
  "parent_command_id": null,
  "target": {
    "device": "laptop",
    "capability": "kum"
  },
  "action": "run_task",
  "input": {
    "task": "Check KUM status and report any unhealthy component."
  },
  "policy": {
    "approval": "auto_safe",
    "timeout_ms": 60000
  }
}
```

## Result channel

Every command must return a machine-readable result, not only free-form text.

Example:

```json
{
  "protocol_version": "1.0",
  "session_id": "sess_...",
  "command_id": "cmd_...",
  "status": "succeeded",
  "summary": "KUM fast access is enabled and active.",
  "data": {
    "service": "kum-fast-access.service",
    "enabled": true,
    "active": true
  },
  "observations": [],
  "error": null,
  "next_actions": []
}
```

Statuses:

- `queued`
- `running`
- `succeeded`
- `failed`
- `needs_approval`
- `cancelled`
- `timed_out`

## Session / correlation loop

Required identifiers:

- `session_id` — one conversational task chain.
- `command_id` — one bridge command.
- `parent_command_id` — links a follow-up command to the result that caused it.
- `trace_id` — optional cross-component diagnostic correlation.

The conversational model owns the cross-command decision loop.

ClosePaw owns execution of an individual delegated device task and may internally use its existing ReAct loop.

This separation avoids a nested low-level agent loop where ChatGPT micromanages taps and swipes.

## Device routing

The request should target a logical capability rather than a transport.

Examples:

- `device=laptop, capability=kum`
- `device=phone, capability=android_ui`
- `device=auto, capability=browser`

Bridge Core resolves the physical route.

Internal routes may include Termux, local service, SSH, Accessibility, Shizuku, browser automation, or a PC agent. Those details are hidden from ChatGPT unless troubleshooting is requested.

## Transport

Preferred runtime topology:

```
Voice client / model
        |
        | tool call
        v
Bridge Gateway
        |
        | authenticated bidirectional session
        v
Device Router
   +----+-----+
   |          |
Android     PC Agent
ClosePaw
```

Device-side connections should normally be outbound so the phone/PC does not require an inbound public port.

A persistent WebSocket or equivalent event stream can carry command and result events between the gateway and device agents.

## OpenAI adapters

### Adapter A — Realtime API (first working E2E)

Expose Bridge Core as function tools to an OpenAI Realtime/GPT-Live session.

Flow:

1. User speaks.
2. Model emits `run_task`.
3. Client/backend sends the command to Bridge Gateway.
4. ClosePaw/PC agent executes.
5. Gateway returns the function result.
6. Model evaluates the result.
7. Model either calls another bridge tool or speaks the final answer.

This is the first implementation target because it supports tool-result loops directly.

### Adapter B — native ChatGPT plugin/MCP

Keep a thin MCP facade over the same Bridge Core.

When the user's ChatGPT plan/surface allows private write-capable custom MCP/plugin use, the native ChatGPT Voice path can use the same tools without redesigning the device side.

## Approval policy

Classify actions before dispatch:

- `safe_read` — status/read-only checks; auto-execute.
- `reversible_write` — changes with easy rollback; policy dependent.
- `external_side_effect` — sends messages, posts content, purchases, deletes data; require explicit approval.
- `blocked` — sensitive apps/actions prohibited by ClosePaw safety policy.

Approval must be enforced by Bridge Core / device policy, not only by the model prompt.

## Security baseline

- Per-device identity.
- Short-lived session tokens.
- Signed or authenticated command transport.
- Replay protection using command IDs/nonces.
- Device/capability allowlists.
- Existing ClosePaw hard blocks remain authoritative.
- Structured audit trail for each command/result chain.
- Never expose raw secrets in result payloads.

## MVP acceptance test

Voice request:

> "Check KUM status on my laptop."

Expected:

1. Voice model selects `run_task`.
2. Bridge routes to the laptop.
3. PC agent performs the existing KUM health/status check.
4. A structured result returns to the same session.
5. The model explains the state by voice.
6. If unhealthy, the model may issue one appropriate diagnostic follow-up command.
7. No Termux/SSH/adapter selection is exposed to the user.

## Implementation phases

### Phase 1 — Protocol and simulator

- Define command/result schemas.
- Implement session/correlation IDs.
- Build an in-process fake device adapter.
- Add contract tests.

### Phase 2 — PC path

- Add a PC agent adapter.
- Expose KUM status as the first real capability.
- Verify command -> result round trip.

### Phase 3 — Android path

- Add a Bridge ingress to ClosePaw.
- Reuse existing ClosePaw task execution and safety policy.
- Verify Android task round trip.

### Phase 4 — Realtime voice adapter

- Connect Bridge Core as Realtime function tools.
- Verify multi-turn command/result/next-command behavior.
- Measure latency and failure recovery.

### Phase 5 — native ChatGPT adapter

- Add MCP/plugin facade over the same Bridge Core when private write-capable access is available for the target ChatGPT account/surface.

## Non-goals for the first version

- Do not send every Android UI primitive through ChatGPT.
- Do not make Termux or SSH part of the public protocol.
- Do not create separate command protocols for phone and PC.
- Do not bind the bridge contract to one OpenAI model or API.
- Do not bypass ClosePaw safety controls.
