#!/usr/bin/env python3
"""Minimal ChatGPT-facing ClosePaw MCP server.

Product boundary:
    ChatGPT Voice -> ClosePaw.run_task(...) -> result -> ChatGPT Voice

This MVP intentionally contains no KUM-, Android-, Termux-, SSH-, or
Bridge-Core-specific routing. It proves the ChatGPT <-> ClosePaw round trip
first. Device execution is an internal ClosePaw concern added after this path
is verified.
"""

from __future__ import annotations

from typing import Any

from mcp.server import MCPServer
from mcp.types import ToolAnnotations


mcp = MCPServer(
    "ClosePaw",
    instructions=(
        "ClosePaw is the user's device-control agent. Use run_task for requests "
        "the user wants ClosePaw to handle. The current MVP supports ClosePaw "
        "status/connectivity tests only; return unsupported results honestly "
        "for other tasks."
    ),
)


_STATUS_TERMS = (
    "status",
    "health",
    "ping",
    "test",
    "reachable",
    "connection",
    "상태",
    "연결",
    "테스트",
    "응답",
)


def execute_task(task: str, target: str | None = None) -> dict[str, Any]:
    """Minimal ClosePaw executor used to prove the native ChatGPT round trip."""
    cleaned = task.strip()
    if not cleaned:
        return {
            "status": "failed",
            "summary": "ClosePaw received an empty task.",
            "handled": False,
            "target": target,
        }

    lowered = cleaned.casefold()
    if any(term in lowered for term in _STATUS_TERMS):
        return {
            "status": "succeeded",
            "summary": "ClosePaw is reachable and responding.",
            "handled": True,
            "target": target or "closepaw",
            "task_received": cleaned,
            "capability": "status",
        }

    return {
        "status": "unsupported",
        "summary": (
            "ClosePaw received the task, but this MVP only supports "
            "status/connectivity tests."
        ),
        "handled": False,
        "target": target,
        "task_received": cleaned,
        "supported_capabilities": ["status"],
    }


@mcp.tool(
    title="Run a ClosePaw task",
    description=(
        "Send one high-level task to ClosePaw. Use this whenever the user asks "
        "ClosePaw to check or control something. The current MVP can only verify "
        "that ClosePaw is reachable and responding; it does not yet execute KUM "
        "or device-control actions."
    ),
    annotations=ToolAnnotations(
        read_only_hint=True,
        open_world_hint=False,
        destructive_hint=False,
        idempotent_hint=True,
    ),
)
def run_task(task: str, target: str | None = None) -> dict[str, Any]:
    """Run a high-level task through ClosePaw."""
    return execute_task(task, target)


if __name__ == "__main__":
    mcp.run()
