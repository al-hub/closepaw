#!/usr/bin/env python3
"""ClosePaw MCP server for ChatGPT plugins.

Designed to run locally on the user's laptop and be reached from ChatGPT
through Secure MCP Tunnel. The server itself stays private and uses stdio.
"""

from __future__ import annotations

import sys
import uuid
from pathlib import Path
from typing import Any

from mcp.server import MCPServer

CHATGPT_BRIDGE_ROOT = Path(__file__).resolve().parents[1] / "chatgpt-bridge"
if str(CHATGPT_BRIDGE_ROOT) not in sys.path:
    sys.path.insert(0, str(CHATGPT_BRIDGE_ROOT))

from closepaw_bridge_core import BridgeCommand, BridgeCore, DeviceRouter, InMemorySessionStore
from closepaw_bridge_core.kum_adapter import KumStatusAdapter


mcp = MCPServer("ClosePaw")
_core = BridgeCore(
    router=DeviceRouter([KumStatusAdapter()]),
    sessions=InMemorySessionStore(),
)


def execute_kum_status() -> dict[str, Any]:
    """Execute the first safe, read-only ClosePaw capability."""
    session_id = f"mcp-{uuid.uuid4().hex}"
    command_id = f"cmd-{uuid.uuid4().hex}"
    command = BridgeCommand.from_dict(
        {
            "protocol_version": "1.0",
            "session_id": session_id,
            "command_id": command_id,
            "parent_command_id": None,
            "target": {"device": "laptop", "capability": "kum"},
            "action": "run_task",
            "input": {"task": "Check KUM status"},
            "policy": {"approval": "auto_safe"},
        }
    )
    return _core.dispatch(command).to_dict()


@mcp.tool()
def get_kum_status() -> dict[str, Any]:
    """Check KUM status on the user's laptop.

    Use this when the user asks whether KUM or KUM Fast Access is running,
    healthy, enabled, or active on their laptop. This tool is read-only.
    """
    return execute_kum_status()


if __name__ == "__main__":
    mcp.run()
