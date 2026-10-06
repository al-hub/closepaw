import asyncio

import server


def test_status_task_proves_closepaw_round_trip():
    result = server.execute_task("ClosePaw 상태 확인해줘")

    assert result["status"] == "succeeded"
    assert result["handled"] is True
    assert result["capability"] == "status"
    assert result["target"] == "closepaw"
    assert result["task_received"] == "ClosePaw 상태 확인해줘"


def test_optional_target_is_preserved_without_creating_routing_logic():
    result = server.execute_task("연결 테스트", target="laptop")

    assert result["status"] == "succeeded"
    assert result["target"] == "laptop"


def test_unknown_task_is_not_faked_as_executed():
    result = server.execute_task("브라우저 열어줘", target="laptop")

    assert result["status"] == "unsupported"
    assert result["handled"] is False
    assert result["supported_capabilities"] == ["status"]


def test_empty_task_fails_cleanly():
    result = server.execute_task("   ")

    assert result["status"] == "failed"
    assert result["handled"] is False


def test_chatgpt_surface_exposes_only_run_task():
    tools = asyncio.run(server.mcp.list_tools())

    assert [tool.name for tool in tools] == ["run_task"]
    tool = tools[0]
    assert tool.annotations.read_only_hint is True
    assert tool.annotations.open_world_hint is False
    assert tool.annotations.destructive_hint is False
    assert tool.annotations.idempotent_hint is True
