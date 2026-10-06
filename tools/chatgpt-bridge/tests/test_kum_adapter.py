from closepaw_bridge_core import BridgeCommand, CommandStatus
from closepaw_bridge_core.kum_adapter import CommandExecution, KumStatusAdapter


class FakeRunner:
    def __init__(self, states):
        self.states = iter(states)
        self.calls = []

    def run(self, argv, timeout_seconds):
        self.calls.append((tuple(argv), timeout_seconds))
        value = next(self.states)
        return CommandExecution(returncode=0, stdout=value + "\n", stderr="")


def command(device="laptop"):
    return BridgeCommand.from_dict(
        {
            "session_id": "sess-kum",
            "command_id": "cmd-kum",
            "target": {"device": device, "capability": "kum"},
            "action": "run_task",
            "input": {"task": "Check KUM status"},
        }
    )


def test_kum_status_adapter_uses_fixed_read_only_probes():
    runner = FakeRunner(["enabled", "active"])
    adapter = KumStatusAdapter(runner)

    result = adapter.execute(command())

    assert result.status == CommandStatus.SUCCEEDED
    assert result.data == {
        "service": "kum-fast-access.service",
        "enabled": True,
        "active": True,
    }
    assert [call[0] for call in runner.calls] == [
        ("systemctl", "--user", "is-enabled", "kum-fast-access.service"),
        ("systemctl", "--user", "is-active", "kum-fast-access.service"),
    ]


def test_kum_status_adapter_returns_diagnostic_hint_when_unhealthy():
    adapter = KumStatusAdapter(FakeRunner(["enabled", "inactive"]))

    result = adapter.execute(command())

    assert result.status == CommandStatus.SUCCEEDED
    assert result.data["active"] is False
    assert result.next_actions == ("diagnose_kum_fast_access",)
    assert "service activity=inactive" in result.observations


def test_kum_adapter_does_not_accept_phone_target():
    adapter = KumStatusAdapter(FakeRunner(["enabled", "active"]))

    assert adapter.supports(command(device="phone")) is False
