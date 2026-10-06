#!/usr/bin/env python3
"""JSON-in / JSON-out reference transport for the bridge core."""

from __future__ import annotations

import json
import sys

from closepaw_bridge_core import BridgeCommand, BridgeCore, DeviceRouter, InMemorySessionStore
from closepaw_bridge_core.kum_adapter import KumStatusAdapter
from closepaw_bridge_core.models import ProtocolError


def build_core() -> BridgeCore:
    return BridgeCore(
        router=DeviceRouter([KumStatusAdapter()]),
        sessions=InMemorySessionStore(),
    )


def main() -> int:
    core = build_core()
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            command = BridgeCommand.from_dict(json.loads(line))
            result = core.dispatch(command).to_dict()
        except (json.JSONDecodeError, ProtocolError) as exc:
            result = {"protocol_version": "1.0", "status": "failed", "error": str(exc)}
        sys.stdout.write(json.dumps(result, separators=(",", ":")) + "\n")
        sys.stdout.flush()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
