"""Session/correlation state. No transport or device execution lives here."""

from __future__ import annotations

from .models import BridgeCommand


class CorrelationError(ValueError):
    pass


class InMemorySessionStore:
    def __init__(self) -> None:
        self._commands: dict[str, set[str]] = {}

    def register(self, command: BridgeCommand) -> None:
        commands = self._commands.setdefault(command.session_id, set())
        if command.command_id in commands:
            raise CorrelationError("duplicate_command_id")
        if command.parent_command_id is not None and command.parent_command_id not in commands:
            raise CorrelationError("unknown_parent_command_id")
        commands.add(command.command_id)
