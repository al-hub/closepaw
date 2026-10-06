"""Command orchestration: validate correlation, route once, normalize result."""

from __future__ import annotations

from .models import BridgeCommand, BridgeResult, CommandStatus
from .router import DeviceRouter, NoDeviceAdapterError
from .session import CorrelationError, InMemorySessionStore


class BridgeCore:
    def __init__(self, router: DeviceRouter, sessions: InMemorySessionStore) -> None:
        self._router = router
        self._sessions = sessions

    def dispatch(self, command: BridgeCommand) -> BridgeResult:
        try:
            self._sessions.register(command)
        except CorrelationError as exc:
            return self._failure(command, "Correlation rejected.", str(exc))

        try:
            adapter = self._router.route(command)
        except NoDeviceAdapterError as exc:
            return self._failure(command, "No execution path is available.", str(exc))

        try:
            outcome = adapter.execute(command)
        except Exception as exc:  # adapter boundary: do not leak executor failures
            return self._failure(command, "Device execution failed.", type(exc).__name__)

        return BridgeResult(
            session_id=command.session_id,
            command_id=command.command_id,
            status=outcome.status,
            summary=outcome.summary,
            data=outcome.data,
            observations=outcome.observations,
            error=outcome.error,
            next_actions=outcome.next_actions,
        )

    @staticmethod
    def _failure(command: BridgeCommand, summary: str, error: str) -> BridgeResult:
        return BridgeResult(
            session_id=command.session_id,
            command_id=command.command_id,
            status=CommandStatus.FAILED,
            summary=summary,
            error=error,
        )
