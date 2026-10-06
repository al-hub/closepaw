from closepaw_bridge_core import (
    BridgeCommand,
    BridgeCore,
    CommandStatus,
    DeviceRouter,
    InMemorySessionStore,
)
from closepaw_bridge_core.models import AdapterOutcome


class FakeAdapter:
    id = "fake"

    def __init__(self):
        self.calls = []

    def supports(self, command):
        return command.target.capability == "fake"

    def execute(self, command):
        self.calls.append(command)
        return AdapterOutcome(
            status=CommandStatus.SUCCEEDED,
            summary="done",
            data={"echo": command.input.get("task")},
        )


def command(command_id="cmd-1", parent=None, capability="fake"):
    return BridgeCommand.from_dict(
        {
            "protocol_version": "1.0",
            "session_id": "sess-1",
            "command_id": command_id,
            "parent_command_id": parent,
            "target": {"device": "auto", "capability": capability},
            "action": "run_task",
            "input": {"task": "check status"},
        }
    )


def test_dispatch_preserves_correlation_and_normalizes_result():
    adapter = FakeAdapter()
    core = BridgeCore(DeviceRouter([adapter]), InMemorySessionStore())

    result = core.dispatch(command())

    assert result.status == CommandStatus.SUCCEEDED
    assert result.session_id == "sess-1"
    assert result.command_id == "cmd-1"
    assert result.data == {"echo": "check status"}
    assert len(adapter.calls) == 1


def test_follow_up_command_accepts_known_parent_in_same_session():
    adapter = FakeAdapter()
    core = BridgeCore(DeviceRouter([adapter]), InMemorySessionStore())

    first = core.dispatch(command("cmd-1"))
    second = core.dispatch(command("cmd-2", parent="cmd-1"))

    assert first.status == CommandStatus.SUCCEEDED
    assert second.status == CommandStatus.SUCCEEDED
    assert len(adapter.calls) == 2


def test_unknown_parent_is_rejected_before_device_execution():
    adapter = FakeAdapter()
    core = BridgeCore(DeviceRouter([adapter]), InMemorySessionStore())

    result = core.dispatch(command("cmd-2", parent="missing"))

    assert result.status == CommandStatus.FAILED
    assert result.error == "unknown_parent_command_id"
    assert adapter.calls == []


def test_duplicate_command_id_is_rejected_before_device_execution():
    adapter = FakeAdapter()
    core = BridgeCore(DeviceRouter([adapter]), InMemorySessionStore())

    assert core.dispatch(command()).status == CommandStatus.SUCCEEDED
    result = core.dispatch(command())

    assert result.status == CommandStatus.FAILED
    assert result.error == "duplicate_command_id"
    assert len(adapter.calls) == 1


def test_missing_device_adapter_returns_structured_failure():
    core = BridgeCore(DeviceRouter([]), InMemorySessionStore())

    result = core.dispatch(command(capability="missing"))

    assert result.status == CommandStatus.FAILED
    assert result.summary == "No execution path is available."
    assert "no adapter" in result.error
