"""Realtime function-call adapter over the transport-neutral Bridge Core."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any, Mapping

CHATGPT_BRIDGE_ROOT = Path(__file__).resolve().parents[1] / "chatgpt-bridge"
if str(CHATGPT_BRIDGE_ROOT) not in sys.path:
    sys.path.insert(0, str(CHATGPT_BRIDGE_ROOT))

from closepaw_bridge_core import BridgeCommand, BridgeCore, DeviceRouter, InMemorySessionStore
from closepaw_bridge_core.kum_adapter import KumStatusAdapter
from closepaw_bridge_core.models import ProtocolError


RUN_TASK_TOOL = {
    "type": "function",
    "name": "run_task",
    "description": (
        "Run a high-level task on one of the user's devices through ClosePaw. "
        "Use this for requests that require checking or controlling the laptop, PC, or phone."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "device": {
                "type": "string",
                "enum": ["auto", "laptop", "pc", "phone"],
                "description": "Logical target device. Use auto when the user did not specify one.",
            },
            "capability": {
                "type": "string",
                "enum": ["kum"],
                "description": "The high-level capability required by the task.",
            },
            "task": {
                "type": "string",
                "description": "A concise description of the user's requested task.",
            },
        },
        "required": ["device", "capability", "task"],
        "additionalProperties": False,
    },
}


def realtime_session_config(model: str = "gpt-realtime-2.1") -> dict[str, Any]:
    return {
        "type": "realtime",
        "model": model,
        "instructions": (
            "You are the voice orchestrator for ClosePaw. Be concise and natural for spoken output. "
            "When a request requires the user's laptop, PC, or phone, use run_task instead of claiming "
            "you cannot access the device. After a tool result arrives, judge the result and either call "
            "run_task again only when a follow-up is actually needed, or explain the result to the user. "
            "Never invent device state."
        ),
        "audio": {"output": {"voice": "marin"}},
        "tools": [RUN_TASK_TOOL],
        "tool_choice": "auto",
    }


class RealtimeBridgeAdapter:
    """Maps Realtime function calls to Bridge Core commands and tracks parent correlation."""

    def __init__(self, core: BridgeCore | None = None) -> None:
        self._core = core or BridgeCore(
            router=DeviceRouter([KumStatusAdapter()]),
            sessions=InMemorySessionStore(),
        )
        self._last_command_by_session: dict[str, str] = {}

    def execute(
        self,
        *,
        session_id: str,
        call_id: str,
        function_name: str,
        arguments: str | Mapping[str, Any],
    ) -> dict[str, Any]:
        if function_name != "run_task":
            return self._error(session_id, call_id, "unsupported_function")

        try:
            parsed = json.loads(arguments) if isinstance(arguments, str) else dict(arguments)
        except (json.JSONDecodeError, TypeError, ValueError):
            return self._error(session_id, call_id, "invalid_function_arguments")

        if not isinstance(parsed, dict):
            return self._error(session_id, call_id, "invalid_function_arguments")

        try:
            command = BridgeCommand.from_dict(
                {
                    "protocol_version": "1.0",
                    "session_id": session_id,
                    "command_id": call_id,
                    "parent_command_id": self._last_command_by_session.get(session_id),
                    "target": {
                        "device": parsed.get("device"),
                        "capability": parsed.get("capability"),
                    },
                    "action": "run_task",
                    "input": {"task": parsed.get("task")},
                    "policy": {"approval": "auto_safe"},
                }
            )
        except ProtocolError as exc:
            return self._error(session_id, call_id, str(exc))

        result = self._core.dispatch(command).to_dict()
        if result["status"] != "failed":
            self._last_command_by_session[session_id] = call_id
        return result

    @staticmethod
    def _error(session_id: str, call_id: str, error: str) -> dict[str, Any]:
        return {
            "protocol_version": "1.0",
            "session_id": session_id,
            "command_id": call_id,
            "status": "failed",
            "summary": "Realtime tool request was rejected.",
            "data": {},
            "observations": [],
            "error": error,
            "next_actions": [],
        }
