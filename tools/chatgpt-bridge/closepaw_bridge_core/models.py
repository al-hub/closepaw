"""Bridge protocol models and validation."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Mapping


PROTOCOL_VERSION = "1.0"


class ProtocolError(ValueError):
    pass


class CommandStatus(str, Enum):
    QUEUED = "queued"
    RUNNING = "running"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    NEEDS_APPROVAL = "needs_approval"
    CANCELLED = "cancelled"
    TIMED_OUT = "timed_out"


@dataclass(frozen=True)
class CommandTarget:
    device: str
    capability: str

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "CommandTarget":
        device = value.get("device")
        capability = value.get("capability")
        if not isinstance(device, str) or not device.strip():
            raise ProtocolError("target.device must be a non-empty string")
        if not isinstance(capability, str) or not capability.strip():
            raise ProtocolError("target.capability must be a non-empty string")
        return cls(device=device.strip(), capability=capability.strip())


@dataclass(frozen=True)
class BridgeCommand:
    session_id: str
    command_id: str
    target: CommandTarget
    action: str
    input: Mapping[str, Any] = field(default_factory=dict)
    parent_command_id: str | None = None
    policy: Mapping[str, Any] = field(default_factory=dict)
    protocol_version: str = PROTOCOL_VERSION

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "BridgeCommand":
        if value.get("protocol_version", PROTOCOL_VERSION) != PROTOCOL_VERSION:
            raise ProtocolError("unsupported protocol_version")

        def required_string(name: str) -> str:
            raw = value.get(name)
            if not isinstance(raw, str) or not raw.strip():
                raise ProtocolError(f"{name} must be a non-empty string")
            return raw.strip()

        parent = value.get("parent_command_id")
        if parent is not None and (not isinstance(parent, str) or not parent.strip()):
            raise ProtocolError("parent_command_id must be null or a non-empty string")

        raw_input = value.get("input", {})
        raw_policy = value.get("policy", {})
        if not isinstance(raw_input, Mapping):
            raise ProtocolError("input must be an object")
        if not isinstance(raw_policy, Mapping):
            raise ProtocolError("policy must be an object")
        raw_target = value.get("target")
        if not isinstance(raw_target, Mapping):
            raise ProtocolError("target must be an object")

        return cls(
            session_id=required_string("session_id"),
            command_id=required_string("command_id"),
            parent_command_id=parent.strip() if isinstance(parent, str) else None,
            target=CommandTarget.from_dict(raw_target),
            action=required_string("action"),
            input=dict(raw_input),
            policy=dict(raw_policy),
        )


@dataclass(frozen=True)
class AdapterOutcome:
    status: CommandStatus
    summary: str
    data: Mapping[str, Any] = field(default_factory=dict)
    observations: tuple[str, ...] = ()
    error: str | None = None
    next_actions: tuple[str, ...] = ()


@dataclass(frozen=True)
class BridgeResult:
    session_id: str
    command_id: str
    status: CommandStatus
    summary: str
    data: Mapping[str, Any] = field(default_factory=dict)
    observations: tuple[str, ...] = ()
    error: str | None = None
    next_actions: tuple[str, ...] = ()
    protocol_version: str = PROTOCOL_VERSION

    def to_dict(self) -> dict[str, Any]:
        return {
            "protocol_version": self.protocol_version,
            "session_id": self.session_id,
            "command_id": self.command_id,
            "status": self.status.value,
            "summary": self.summary,
            "data": dict(self.data),
            "observations": list(self.observations),
            "error": self.error,
            "next_actions": list(self.next_actions),
        }
