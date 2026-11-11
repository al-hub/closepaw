"""Updater trust and transaction tests; never operates on a real user daemon."""
import hashlib
import importlib.util
import json
from pathlib import Path
import pytest


def updater():
    path = Path(__file__).resolve().parents[1] / 'closepaw_maintenance.py'
    spec = importlib.util.spec_from_file_location('maintenance', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_only_operator_pinned_immutable_sources_are_accepted(tmp_path):
    m = updater()
    with pytest.raises(m.MaintenanceError, match='unapproved_revision'):
        m.load_pin(tmp_path, 'main')
    with pytest.raises(m.MaintenanceError, match='unapproved_revision'):
        m.load_pin(tmp_path, 'a' * 40)


def test_download_hash_and_syntax_checked_before_replacement(tmp_path):
    m = updater()
    data = b'print("safe")\n'
    assert m.validate_source(data, hashlib.sha256(data).hexdigest()) == data
    with pytest.raises(m.MaintenanceError, match='integrity_failed'):
        m.validate_source(data, '0' * 64)
    bad = b'if !\n'
    with pytest.raises(m.MaintenanceError, match='syntax_failed'):
        m.validate_source(bad, hashlib.sha256(bad).hexdigest())


def test_atomic_writes_are_private_and_preserve_existing_records(tmp_path):
    m = updater()
    token = tmp_path / 'token'
    token.write_bytes(b'unchanged')
    dest = tmp_path / 'bridge.py'
    m.atomic_write(dest, b'old')
    m.atomic_write(dest, b'new')
    assert dest.read_bytes() == b'new'
    assert dest.stat().st_mode & 0o777 == 0o600
    assert token.read_bytes() == b'unchanged'

@pytest.fixture
def transaction(tmp_path, free_port, monkeypatch, request):
    import subprocess, sys, os
    from conftest import AUTH_TOKEN, BridgeProcess, wait_for_health, stop_process
    m = updater()
    root = tmp_path / '.closepaw'
    root.mkdir()
    repo = Path(__file__).resolve().parents[3]
    spec = importlib.util.spec_from_file_location('bundler', repo / 'scripts/bundle-termux-bridge.py')
    bundler = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bundler)
    script = root / 'bridge.py'
    candidate_source = bundler.bundle(repo).encode()
    original = candidate_source
    if getattr(request, 'param', None) == 'saturated':
        original = original.replace(b'    def submit(self, payload):',
            b'    def submit(self, payload):\n        raise JobError(429, "job_store_full_no_confirmed_results")')
    script.write_bytes(original)
    monkeypatch.setenv('HOME', str(tmp_path))
    c = m.MaintenanceController(root, script, free_port, AUTH_TOKEN, lambda *a, **k: None)
    c.launch()
    wait_for_health(BridgeProcess(free_port, tmp_path, c.child))
    revision = 'a' * 40
    m.approve_pin(root, revision, ['0' * 64] * 3)
    candidate = candidate_source.replace(b'217.1', b'217.2')
    monkeypatch.setattr(m, 'download_bundle', lambda *_: candidate)
    yield m, c, original, candidate, revision
    stop_process(c.child)


@pytest.mark.parametrize("transaction", ["saturated"], indirect=True)
def test_update_independent_of_rejected_jobs_and_real_execution(transaction, monkeypatch):
    m, c, original, candidate, revision = transaction
    from conftest import request_json
    assert request_json(c.port, 'POST', '/v1/jobs', {'command': 'printf refused', 'request_id': 'old-refused'}).status == 429
    assert c.call('status')['source_sha256'] == hashlib.sha256(original).hexdigest()
    # Source target has a different runtime revision; no jobs registration used.
    result = c.apply(revision)
    assert result['status'] == 'updated'
    assert result['exit_code'] == 0 and result['stdout'] == 'CLOSEPAW_MAINTENANCE_OK'
    assert c.call('status')['source_sha256'] == hashlib.sha256(candidate).hexdigest()
    assert not c.journal.exists()
    job = request_json(c.port, 'POST', '/v1/jobs', {'command': 'printf restored', 'request_id': 'after-update'}).body['job_id']
    deadline = __import__('time').monotonic() + 3
    while __import__('time').monotonic() < deadline:
        result = request_json(c.port, 'GET', '/v1/jobs/' + job).body
        if result['state'] == 'succeeded': break
        __import__('time').sleep(.05)
    assert result['exit_code'] == 0 and result['stdout'] == 'restored'


def test_startup_failure_rolls_back_and_preserves_token_results_dedup(transaction, monkeypatch):
    m, c, original, candidate, revision = transaction
    token = c.root / 'token'
    token.write_text('private-pairing-preserved')
    from conftest import request_json
    payload = {'command': 'printf retained', 'request_id': 'retained-result'}
    job = request_json(c.port, 'POST', '/v1/jobs', payload).body['job_id']
    deadline = __import__('time').monotonic() + 3
    while __import__('time').monotonic() < deadline:
        snap = request_json(c.port, 'GET', '/v1/jobs/' + job).body
        if snap['state'] == 'succeeded': break
        __import__('time').sleep(.05)
    assert snap['stdout'] == 'retained' and snap['exit_code'] == 0
    before = {p: p.read_bytes() for p in (c.root / 'jobs').glob('*.json')}
    monkeypatch.setattr(m, 'download_bundle', lambda *_: b'raise SystemExit(2)\n')
    result = c.apply(revision)
    assert result['status'] == 'rolled_back'
    assert c.script.read_bytes() == original
    assert token.read_text() == 'private-pairing-preserved'
    assert all(p.read_bytes() == v for p, v in before.items())
    assert request_json(c.port, 'POST', '/v1/jobs', payload).body['job_id'] == job


def test_active_job_defers_then_completion_allows_update(transaction):
    m, c, original, candidate, revision = transaction
    from conftest import request_json
    job = request_json(c.port, 'POST', '/v1/jobs', {'request_id': 'active', 'command': 'sleep 1; printf finished'}).body['job_id']
    assert c.apply(revision)['status'] == 'active_jobs'
    assert c.script.read_bytes() == original
    __import__('time').sleep(1.2)
    snap = request_json(c.port, 'GET', '/v1/jobs/' + job).body
    assert snap['exit_code'] == 0 and snap['stdout'] == 'finished'
    assert c.apply(revision)['status'] == 'updated'


def test_download_failure_reopens_admission_and_is_bounded(transaction, monkeypatch):
    m, c, _, _, revision = transaction
    def fail(*_): raise m.MaintenanceError('download_failed')
    monkeypatch.setattr(m, 'download_bundle', fail)
    for _ in range(3): assert c.apply(revision)['status'] == 'download_failed'
    assert c.apply(revision)['status'] == 'retry_exhausted'
    assert c.call('status')['drained'] is False


def test_interrupted_transaction_never_guesses_or_replays(transaction):
    m, c, original, _, revision = transaction
    c.journal.write_text('{}')
    assert c.resume() is False
    assert c.apply(revision)['status'] == 'interrupted_update_user_required'
    assert c.script.read_bytes() == original
    assert c.child.poll() is None


def test_concurrent_request_has_one_owner(transaction):
    import threading
    m, c, *_ = transaction
    held, release = threading.Event(), threading.Event()
    def owner():
        with c.lock:
            held.set(); release.wait(3)
    thread = threading.Thread(target=owner)
    thread.start(); held.wait(3)
    try: assert c.apply('a' * 40) == {'status': 'recovery_busy'}
    finally: release.set(); thread.join()

@pytest.mark.parametrize('corrupt', [False, True])
def test_unknown_or_corrupt_history_refuses_update_without_replay(transaction, corrupt):
    m, c, original, _, revision = transaction
    from conftest import request_json, stop_process
    job = request_json(c.port, 'POST', '/v1/jobs', {'request_id': 'unknown-test', 'command': 'printf once'}).body['job_id']
    __import__('time').sleep(.2)
    stop_process(c.child)
    record = c.root / 'jobs' / (job + '.json')
    content = json.loads(record.read_text())
    if corrupt:
        record.write_text('{bad')
    else:
        content['outcome_unknown'] = True
        content['state'] = 'interrupted'
        record.write_text(json.dumps(content))
    before = record.read_bytes()
    c.launch(); c.wait_ready()
    assert c.apply(revision)['status'] == ('job_store_unhealthy' if corrupt else 'outcome_unknown')
    assert record.read_bytes() == before
    assert c.script.read_bytes() == original



def test_historical_unknown_retains_original_result_and_never_replays(transaction):
    """A previous effect may have happened, but UNKNOWN must never authorize replay."""
    from time import monotonic, sleep
    from conftest import request_json, stop_process

    m, c, original, _, revision = transaction
    payload = {'request_id': 'historic-unknown', 'command': 'printf 1 >> unknown-effects'}
    submitted = request_json(c.port, 'POST', '/v1/jobs', payload)
    assert submitted.status == 202
    job_id = submitted.body['job_id']
    deadline = monotonic() + 5
    while monotonic() < deadline:
        finished = request_json(c.port, 'GET', '/v1/jobs/' + job_id).body
        if finished['state'] not in ('queued', 'running'):
            break
        sleep(.05)
    else:
        raise AssertionError('fixture command did not finish')

    # Deliberately simulate an interrupted record after an effect already occurred.
    # This is an isolated HOME; no live user record is touched.
    effect = c.root.parent / 'closepaw/workspace/unknown-effects'
    assert finished['exit_code'] == 0 and effect.read_text() == '1'
    stop_process(c.child)
    record = c.root / 'jobs' / (job_id + '.json')
    unknown = json.loads(record.read_text())
    unknown.update(state='interrupted', outcome_unknown=True, exit_code=None)
    record.write_text(json.dumps(unknown))
    original_bytes = record.read_bytes()
    original_sha = hashlib.sha256(original_bytes).hexdigest()

    c.launch()
    c.wait_ready()
    assert c.apply(revision)['status'] == 'outcome_unknown'
    assert c.script.read_bytes() == original
    assert not c.journal.exists()
    assert c.child.poll() is None

    readback = request_json(c.port, 'GET', '/v1/jobs/' + job_id)
    assert readback.status == 200
    assert readback.body['state'] == 'interrupted'
    assert readback.body['outcome_unknown'] is True
    assert readback.body['exit_code'] is None

    # Same request + fingerprint returns the original unknown ID, not another run.
    same = request_json(c.port, 'POST', '/v1/jobs', payload)
    assert same.status == 202 and same.body['job_id'] == job_id
    assert same.body['outcome_unknown'] is True
    conflict = request_json(c.port, 'POST', '/v1/jobs',
                            {'request_id': payload['request_id'], 'command': 'printf 2 >> unknown-effects'})
    assert conflict.status == 409
    assert conflict.body['error'] == 'request_id_conflict'
    assert effect.read_text() == '1'
    assert record.read_bytes() == original_bytes
    assert hashlib.sha256(record.read_bytes()).hexdigest() == original_sha


def test_activate_response_loss_does_not_kill_activated_candidate(transaction, monkeypatch):
    m, c, _, _, revision = transaction
    original_call = c.call
    def lost_reply(action, *args):
        value = original_call(action, *args)
        if action == 'activate': raise m.MaintenanceError('response_timeout_or_absent')
        return value
    monkeypatch.setattr(c, 'call', lost_reply)
    assert c.apply(revision)['status'] == 'activation_outcome_unknown'
    assert c.child.poll() is None
    assert original_call('status')['drained'] is False
    assert c.journal.exists()


def test_corrupt_state_is_bounded_and_fail_closed(tmp_path):
    m = updater()
    (tmp_path / 'maintenance-state.json').write_text('[]')
    c = m.MaintenanceController(tmp_path, tmp_path / 'bridge.py', 12345, 'token', lambda *_: None)
    assert c.state['status'] == 'state_corrupt'
    assert c.state['attempts'] == 3


def test_corrupt_approval_is_refused_not_executed(tmp_path):
    m = updater()
    directory = tmp_path / 'approved-updates'
    directory.mkdir()
    (directory / ('a' * 40 + '.json')).write_text(json.dumps(list(m.MAINTENANCE_FILES)))
    with pytest.raises(m.MaintenanceError, match='unapproved_revision'):
        m.load_pin(tmp_path, 'a' * 40)
