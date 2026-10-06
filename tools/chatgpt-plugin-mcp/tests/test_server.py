import server


class FakeCore:
    def __init__(self):
        self.commands = []

    def dispatch(self, command):
        self.commands.append(command)

        class Result:
            def to_dict(self):
                return {
                    "protocol_version": "1.0",
                    "session_id": command.session_id,
                    "command_id": command.command_id,
                    "status": "succeeded",
                    "summary": "KUM fast access is enabled and active.",
                    "data": {
                        "service": "kum-fast-access.service",
                        "enabled": True,
                        "active": True,
                    },
                    "observations": [],
                    "error": None,
                    "next_actions": [],
                }

        return Result()


def test_execute_kum_status_maps_to_read_only_kum_capability(monkeypatch):
    fake = FakeCore()
    monkeypatch.setattr(server, "_core", fake)

    result = server.execute_kum_status()

    assert result["status"] == "succeeded"
    command = fake.commands[0]
    assert command.target.device == "laptop"
    assert command.target.capability == "kum"
    assert command.action == "run_task"
    assert command.input == {"task": "Check KUM status"}
    assert command.policy == {"approval": "auto_safe"}


def test_each_plugin_call_has_unique_correlation_ids(monkeypatch):
    fake = FakeCore()
    monkeypatch.setattr(server, "_core", fake)

    first = server.execute_kum_status()
    second = server.execute_kum_status()

    assert first["session_id"] != second["session_id"]
    assert first["command_id"] != second["command_id"]


def test_mcp_server_registers_get_kum_status_tool():
    assert callable(server.get_kum_status)
