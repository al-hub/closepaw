"""Default noninteractive environment shared by sync exec and durable jobs."""
import importlib.util
import os
from pathlib import Path

import pytest
from conftest import BRIDGE_SCRIPT
from test_jobs import finished


@pytest.fixture
def bridge_module(monkeypatch):
    monkeypatch.syspath_prepend(str(BRIDGE_SCRIPT.parent))
    spec = importlib.util.spec_from_file_location('shell_environment_bridge', BRIDGE_SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_default_path_appends_user_bin_and_preserves_system_priority(bridge_module, monkeypatch, tmp_path):
    monkeypatch.setenv('HOME', str(tmp_path))
    monkeypatch.setenv('PATH', '/system/first:/system/second')
    result = bridge_module.parse_env(None)
    assert result['PATH'] == f'/system/first:/system/second:{tmp_path}/bin'
    assert result['HOME'] == str(tmp_path)
    assert os.environ['PATH'] == '/system/first:/system/second'  # no daemon-wide mutation


def test_existing_user_bin_is_not_duplicated_or_reordered(bridge_module, monkeypatch, tmp_path):
    monkeypatch.setenv('HOME', str(tmp_path))
    original = f'{tmp_path}/bin:/system/bin'
    monkeypatch.setenv('PATH', original)
    assert bridge_module.parse_env({})['PATH'] == original


@pytest.mark.parametrize('explicit_path', ['/custom/bin', ''])
def test_explicit_path_override_remains_exact(bridge_module, monkeypatch, tmp_path, explicit_path):
    monkeypatch.setenv('HOME', str(tmp_path))
    assert bridge_module.parse_env({'PATH': explicit_path})['PATH'] == explicit_path


def test_missing_path_uses_safe_platform_default(bridge_module, monkeypatch, tmp_path):
    monkeypatch.setenv('HOME', str(tmp_path))
    monkeypatch.delenv('PATH', raising=False)
    assert bridge_module.parse_env(None)['PATH'] == f'{os.defpath}:{tmp_path}/bin'


def test_relative_home_is_not_added_to_search_path(bridge_module, monkeypatch):
    monkeypatch.setenv('HOME', 'relative-home')
    monkeypatch.setenv('PATH', '/system/bin')
    assert bridge_module.parse_env(None)['PATH'] == '/system/bin'


@pytest.mark.parametrize('transport', ['exec', 'jobs'])
def test_user_commands_resolve_without_loading_profiles(bridge_server, transport):
    home = bridge_server.home
    user_bin = home / 'bin'
    user_bin.mkdir()
    tool = user_bin / 'bridge-user-tool'
    tool.write_text('#!/bin/sh\nprintf user-command-ok')
    tool.chmod(0o700)
    # Executing either profile would leave a marker; environment setup must not source them.
    for name in ('.bashrc', '.bash_profile', '.profile'):
        (home / name).write_text('touch "$HOME/profile-was-executed"\n')
    payload = {'command': 'command -v bridge-user-tool; bridge-user-tool'}
    if transport == 'exec':
        result = bridge_server.post_json('/v1/exec', payload).body
    else:
        payload['request_id'] = 'default-user-path'
        submitted = bridge_server.post_json('/v1/jobs', payload)
        result = finished(bridge_server, submitted.body['job_id'])
    assert result['exit_code'] == 0
    assert result['stdout'] == str(tool) + '\nuser-command-ok'
    assert not (home / 'profile-was-executed').exists()
