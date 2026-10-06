import json

from realtime_adapter import RUN_TASK_TOOL, RealtimeBridgeAdapter, realtime_session_config
from closepaw_bridge_core.models import AdapterOutcome, CommandStatus


class FakeCore:
    def __init__(self):
        self.commands = []

    def dispatch(self, command):
        self.commands.append(command)
        return type(
            "Result",
            (),
            {
                "to_dict": lambda self: {
                    "protocol_version": "1.0",
                    "session_id": command.session_id,
                    "command_id": command.command_id,
                    "status": "succeeded",
                    "summary": "ok",
                    "data": {},
                    "observations": [],
                    "error": None,
                    "next_actions": [],
                }
            },
        )()


def test_session_config_exposes_run_task_function_tool():
    config = realtime_session_config()

    assert config["model"] == "gpt-realtime-2.1"
    assert config["tool_choice"] == "auto"
    assert config["tools"] == [RUN_TASK_TOOL]
    assert RUN_TASK_TOOL["parameters"]["properties"]["capability"]["enum"] == ["kum"]


def test_realtime_adapter_maps_function_call_to_bridge_command():
    core = FakeCore()
    adapter = RealtimeBridgeAdapter(core)

    result = adapter.execute(
        session_id="sess-live",
        call_id="call-1",
        function_name="run_task",
        arguments=json.dumps({
            "device": "laptop",
            "capability": "kum",
            "task": "Check KUM status",
        }),
    )

    assert result["status"] == "succeeded"
    command = core.commands[0]
    assert command.session_id == "sess-live"
    assert command.command_id == "call-1"
    assert command.parent_command_id is None
    assert command.target.device == "laptop"
    assert command.target.capability == "kum"
    assert command.input == {"task": "Check KUM status"}


def test_second_tool_call_is_correlated_to_previous_command():
    core = FakeCore()
    adapter = RealtimeBridgeAdapter(core)

    args = {"device": "laptop", "capability": "kum", "task": "Check KUM status"}
    adapter.execute(
        session_id="sess-live", call_id="call-1", function_name="run_task", arguments=args
    )
    adapter.execute(
        session_id="sess-live", call_id="call-2", function_name="run_task", arguments=args
    )

    assert core.commands[1].parent_command_id == "call-1"


def test_failed_tool_call_does_not_become_parent():
    class FailingCore(FakeCore):
        def dispatch(self, command):
            self.commands.append(command)
            return type(
                "Result",
                (),
                {
                    "to_dict": lambda self: {
                        "protocol_version": "1.0",
                        "session_id": command.session_id,
                        "command_id": command.command_id,
                        "status": "failed",
                        "summary": "failed",
                        "data": {},
                        "observations": [],
                        "error": "x",
                        "next_actions": [],
                    }
                },
            )()

    core = FailingCore()
    adapter = RealtimeBridgeAdapter(core)
    args = {"device": "laptop", "capability": "kum", "task": "Check KUM status"}

    adapter.execute(
        session_id="sess-live", call_id="call-1", function_name="run_task", arguments=args
    )
    adapter.execute(
        session_id="sess-live", call_id="call-2", function_name="run_task", arguments=args
    )

    assert core.commands[1].parent_command_id is None


def test_rejects_unknown_function_without_dispatch():
    core = FakeCore()
    adapter = RealtimeBridgeAdapter(core)

    result = adapter.execute(
        session_id="sess-live",
        call_id="call-1",
        function_name="shell",
        arguments="{}",
    )

    assert result["status"] == "failed"
    assert result["error"] == "unsupported_function"
    assert core.commands == []
