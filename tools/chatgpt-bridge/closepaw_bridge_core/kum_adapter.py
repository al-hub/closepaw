"""Read-only PC/KUM capability adapter."""

from __future__ import annotations

import subprocess
from dataclasses import dataclass
from typing import Protocol, Sequence

from .models import AdapterOutcome, BridgeCommand, CommandStatus


SERVICE = "kum-fast-access.service"


@dataclass(frozen=True)
class CommandExecution:
    returncode: int
    stdout: str
    stderr: str


class CommandRunner(Protocol):
    def run(self, argv: Sequence[str], timeout_seconds: float) -> CommandExecution: ...


class SubprocessCommandRunner:
    def run(self, argv: Sequence[str], timeout_seconds: float) -> CommandExecution:
        completed = subprocess.run(
            list(argv),
            capture_output=True,
            text=True,
            timeout=timeout_seconds,
            check=False,
        )
        return CommandExecution(
            returncode=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
        )


class KumStatusAdapter:
    """Maps a high-level KUM status request to fixed, read-only service probes."""

    id = "pc-kum-status"

    def __init__(self, runner: CommandRunner | None = None) -> None:
        self._runner = runner or SubprocessCommandRunner()

    def supports(self, command: BridgeCommand) -> bool:
        return (
            command.action == "run_task"
            and command.target.capability == "kum"
            and command.target.device in {"auto", "pc", "laptop"}
        )

    def execute(self, command: BridgeCommand) -> AdapterOutcome:
        enabled = self._probe(("systemctl", "--user", "is-enabled", SERVICE))
        active = self._probe(("systemctl", "--user", "is-active", SERVICE))
        healthy = enabled == "enabled" and active == "active"

        if healthy:
            return AdapterOutcome(
                status=CommandStatus.SUCCEEDED,
                summary="KUM fast access is enabled and active.",
                data={"service": SERVICE, "enabled": True, "active": True},
            )

        observations = tuple(
            item
            for item in (
                None if enabled == "enabled" else f"service enablement={enabled}",
                None if active == "active" else f"service activity={active}",
            )
            if item is not None
        )
        return AdapterOutcome(
            status=CommandStatus.SUCCEEDED,
            summary="KUM status check completed; fast access needs attention.",
            data={
                "service": SERVICE,
                "enabled": enabled == "enabled",
                "active": active == "active",
                "enabled_state": enabled,
                "active_state": active,
            },
            observations=observations,
            next_actions=("diagnose_kum_fast_access",),
        )

    def _probe(self, argv: Sequence[str]) -> str:
        try:
            result = self._runner.run(argv, timeout_seconds=5.0)
        except subprocess.TimeoutExpired:
            return "timed_out"
        except OSError:
            return "unavailable"
        value = result.stdout.strip()
        if value:
            return value
        if result.stderr.strip():
            return result.stderr.strip()
        return f"exit_{result.returncode}"
