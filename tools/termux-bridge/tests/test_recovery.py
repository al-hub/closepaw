"""Real process regression tests for Termux-side recovery (no Android RUN_COMMAND)."""
import importlib.util
import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

import pytest
from conftest import AUTH_TOKEN, BRIDGE_SCRIPT, BridgeProcess, request_json, stop_process, wait_for_health


def eventually(check, timeout=8):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except (OSError, ValueError):
            pass
        time.sleep(.05)
    raise AssertionError('condition did not become true')


def launch_supervisor(home, port, token=AUTH_TOKEN):
    return subprocess.Popen(
        [sys.executable, str(BRIDGE_SCRIPT), '--supervise', '--port', str(port),
         '--supervisor-interval-sec', '.1'],
        env={**os.environ, 'HOME': str(home), 'CLOSEPAW_BRIDGE_TOKEN': token},
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
    )


def pid(home):
    return json.loads((home / '.closepaw/bridge.pid').read_text())['pid']


def test_killed_daemon_recovers_and_retains_pairing(tmp_path, free_port):
    supervisor = launch_supervisor(tmp_path, free_port)
    bridge = BridgeProcess(free_port, tmp_path, supervisor)
    try:
        wait_for_health(bridge)
        user_bin = tmp_path / 'bin'
        user_bin.mkdir()
        user_tool = user_bin / 'recovered-user-tool'
        user_tool.write_text('#!/bin/sh\nprintf recovered-path-ok')
        user_tool.chmod(0o700)
        original = pid(tmp_path)
        os.kill(original, signal.SIGKILL)
        eventually(lambda: pid(tmp_path) != original)
        assert request_json(free_port, 'GET', '/v1/jobs').status == 200
        result = bridge.post_json('/v1/exec', {'command': 'printf recovered'})
        assert result.body['exit_code'] == 0
        assert result.body['stdout'] == 'recovered'
        resolved = bridge.post_json('/v1/exec', {'command': 'command -v recovered-user-tool; recovered-user-tool'})
        assert resolved.body['exit_code'] == 0
        assert resolved.body['stdout'] == str(user_tool) + '\nrecovered-path-ok'
        log = (tmp_path / '.closepaw/recovery.jsonl').read_text()
        assert '"exit_code":-9' in log
        assert AUTH_TOKEN not in log
        assert not (tmp_path / '.closepaw/token').exists()  # no new pairing material
    finally:
        stop_process(supervisor)


def test_duplicate_supervisor_does_not_restart_daemon(tmp_path, free_port):
    first = launch_supervisor(tmp_path, free_port)
    try:
        wait_for_health(BridgeProcess(free_port, tmp_path, first))
        original = pid(tmp_path)
        second = launch_supervisor(tmp_path, free_port)
        assert second.wait(timeout=3) == 0
        second.communicate()
        assert pid(tmp_path) == original
    finally:
        stop_process(first)


def test_supervisor_adopts_healthy_daemon(bridge_server):
    original = bridge_server.process.pid
    supervisor = launch_supervisor(bridge_server.home, bridge_server.port)
    try:
        eventually(lambda: (bridge_server.home / '.closepaw/recovery.json').exists())
        eventually(lambda: json.loads((bridge_server.home / '.closepaw/recovery.json').read_text())['event'] == 'ready')
        assert bridge_server.process.poll() is None
        assert pid(bridge_server.home) == original
    finally:
        stop_process(supervisor)
    assert bridge_server.process.poll() is None  # adopted process is not owned


def test_auth_mismatch_never_restarts_or_replaces_pairing(bridge_server):
    supervisor = launch_supervisor(bridge_server.home, bridge_server.port, 'wrong-token-' + 'x' * 32)
    assert supervisor.wait(timeout=4) == 3
    supervisor.communicate()
    assert bridge_server.process.poll() is None
    log = (bridge_server.home / '.closepaw/recovery.jsonl').read_text()
    assert 'auth_mismatch' in log
    assert 'wrong-token' not in log
    assert request_json(bridge_server.port, 'GET', '/v1/jobs').status == 200


def test_boot_reentry_after_supervisor_stop(tmp_path, free_port):
    first = launch_supervisor(tmp_path, free_port)
    try:
        wait_for_health(BridgeProcess(free_port, tmp_path, first))
    finally:
        stop_process(first)
    second = launch_supervisor(tmp_path, free_port)
    try:
        wait_for_health(BridgeProcess(free_port, tmp_path, second))
        assert request_json(free_port, 'GET', '/v1/jobs').status == 200
    finally:
        stop_process(second)


def test_legacy_persistent_flag_is_supported(tmp_path, free_port):
    process = subprocess.Popen(
        [sys.executable, str(BRIDGE_SCRIPT), '--persistent', '--port', str(free_port)],
        env={**os.environ, 'HOME': str(tmp_path), 'CLOSEPAW_BRIDGE_TOKEN': AUTH_TOKEN},
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    try:
        bridge = BridgeProcess(free_port, tmp_path, process)
        wait_for_health(bridge)
        assert bridge.get_json('/v1/health').body['idle_timeout_sec'] == 0
    finally:
        stop_process(process)


@pytest.fixture
def recovery_module(monkeypatch):
    monkeypatch.syspath_prepend(str(BRIDGE_SCRIPT.parent))
    spec = importlib.util.spec_from_file_location('recovery_bridge', BRIDGE_SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_absent_daemon_is_distinct_from_occupied_port(recovery_module, free_port):
    import socket
    assert recovery_module.recovery_probe(free_port, AUTH_TOKEN) == 'daemon_absent'
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', free_port))
        listener.listen()
        assert recovery_module.recovery_probe(free_port, AUTH_TOKEN) == 'port_conflict'


def test_bounded_retries_without_token_logging(recovery_module, monkeypatch, tmp_path, free_port):
    monkeypatch.setenv('HOME', str(tmp_path))
    monkeypatch.setattr(recovery_module, 'recovery_probe', lambda *_: 'daemon_absent')
    class NoWait:
        def is_set(self): return False
        def wait(self, _): return False
    monkeypatch.setattr(recovery_module.threading, 'Event', NoWait)
    attempts = []
    class FailedChild:
        pid = 123
        def __init__(self, *args, **kwargs): attempts.append(args)
        def poll(self): return 2
    monkeypatch.setattr(recovery_module.subprocess, 'Popen', FailedChild)
    from types import SimpleNamespace
    args = SimpleNamespace(port=free_port, host='127.0.0.1', auth_token=AUTH_TOKEN, supervisor_interval_sec=.1)
    assert recovery_module.supervise(args) == 4
    assert len(attempts) == 5
    log = (tmp_path / '.closepaw/recovery.jsonl').read_text()
    assert 'recovery_exhausted' in log
    assert AUTH_TOKEN not in log


def test_diagnostics_rotate_and_are_private(recovery_module, monkeypatch, tmp_path):
    monkeypatch.setenv('HOME', str(tmp_path))
    root = tmp_path / '.closepaw'
    root.mkdir()
    (root / 'recovery.jsonl').write_text('x' * 66000)
    recovery_module.recovery_event('daemon_absent')
    assert (root / 'recovery.previous.jsonl').exists()
    assert (root / 'recovery.jsonl').stat().st_size < 1000
    assert (root / 'recovery.json').stat().st_mode & 0o777 == 0o600


def test_adopted_killed_child_recovers_before_original_parent_reaps_it(tmp_path, free_port, bridge_factory):
    """Android may retain an adopted daemon's zombie PID; no caller wait() is required."""
    daemon = bridge_factory(free_port, tmp_path)
    supervisor = launch_supervisor(tmp_path, free_port)
    try:
        eventually(lambda: (tmp_path / '.closepaw/recovery.json').exists())
        eventually(lambda: json.loads((tmp_path / '.closepaw/recovery.json').read_text())['event'] == 'ready')
        original = daemon.process.pid
        os.kill(original, signal.SIGKILL)
        # Deliberately no daemon.process.poll()/wait(): the original parent has not reaped it.
        eventually(lambda: pid(tmp_path) != original)
        assert request_json(free_port, 'GET', '/v1/jobs').status == 200
    finally:
        stop_process(supervisor)
        # Reap only after observing recovery, and clean up the new owned child on supervisor exit.
        daemon.process.wait(timeout=3)
