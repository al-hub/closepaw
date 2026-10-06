"""Capability-based device routing."""

from __future__ import annotations

from typing import Protocol

from .models import AdapterOutcome, BridgeCommand


class DeviceAdapter(Protocol):
    @property
    def id(self) -> str: ...

    def supports(self, command: BridgeCommand) -> bool: ...

    def execute(self, command: BridgeCommand) -> AdapterOutcome: ...


class NoDeviceAdapterError(LookupError):
    pass


class DeviceRouter:
    def __init__(self, adapters: list[DeviceAdapter]) -> None:
        self._adapters = tuple(adapters)

    def route(self, command: BridgeCommand) -> DeviceAdapter:
        for adapter in self._adapters:
            if adapter.supports(command):
                return adapter
        raise NoDeviceAdapterError(
            f"no adapter for device={command.target.device} capability={command.target.capability}"
        )
