import time
from conftest import request_json, stop_process


def submit(bridge, command='printf hello', **kwargs):
    return bridge.post_json('/v1/jobs', dict(request_id='req-one', command=command, **kwargs))


def finished(bridge, job_id):
    for _ in range(100):
        response = bridge.get_json('/v1/jobs/' + job_id)
        if response.body['state'] not in ('queued', 'running'):
            return response.body
        time.sleep(.05)
    raise AssertionError('job did not finish')


def test_job_survives_submission_connection_and_pages_output(bridge_server):
    response = submit(bridge_server)
    assert response.status == 202
    result = finished(bridge_server, response.body['job_id'])
    assert result['state'] == 'succeeded'
    assert result['exit_code'] == 0
    page = bridge_server.get_json('/v1/jobs/' + response.body['job_id'] + '?limit=2')
    assert page.body['stdout'] == 'he'
    assert page.body['stdout_next_offset'] == 2


def test_job_idempotency_and_conflict(bridge_server):
    first = submit(bridge_server)
    assert first.status == 202
    second = submit(bridge_server)
    assert second.body['job_id'] == first.body['job_id']
    assert submit(bridge_server, 'printf different').status == 409


def test_jobs_auth_and_strict_validation(bridge_server):
    assert bridge_server.post_json('/v1/jobs', {}, auth_token=None).status == 401
    assert request_json(bridge_server.port, 'GET', '/v1/jobs/unknown', auth_token=None).status == 401
    for payload in ({'request_id': 'r', 'command': ''}, {'request_id': 'r', 'command': 'true', 'unknown': 1},
                    {'request_id': 'r', 'command': 'true', 'timeout_ms': True},
                    {'request_id': 'r', 'command': 'true', 'env': {'BAD=KEY': 'x'}}):
        assert bridge_server.post_json('/v1/jobs', payload).status == 400


def test_job_cancel_and_timeout(bridge_server):
    response = submit(bridge_server, 'sleep 20')
    assert response.status == 202
    cancel = bridge_server.post_json('/v1/jobs/' + response.body['job_id'] + '/cancel', {})
    assert cancel.status == 200
    assert finished(bridge_server, response.body['job_id'])['state'] == 'cancelled'
    response = bridge_server.post_json('/v1/jobs', {'request_id': 'timeout', 'command': 'sleep 20', 'timeout_ms': 100})
    assert finished(bridge_server, response.body['job_id'])['state'] == 'timed_out'


def test_output_is_bounded(bridge_server):
    response = submit(bridge_server, "printf 123456789", max_output_bytes=4)
    result = finished(bridge_server, response.body['job_id'])
    assert result['stdout'] == '1234'
    assert result['stdout_truncated'] is True


def test_restart_does_not_reexecute(bridge_factory, free_port, tmp_path):
    first = bridge_factory(free_port, tmp_path)
    response = submit(first, 'sleep 20')
    job_id = response.body['job_id']
    stop_process(first.process)
    second = bridge_factory(free_port, tmp_path)
    result = second.get_json('/v1/jobs/' + job_id)
    assert result.body['state'] == 'interrupted'
    assert result.body['outcome_unknown'] is True
    assert submit(second, 'sleep 20').body['job_id'] == job_id


def test_concurrent_duplicate_submission_runs_once(bridge_server):
    import concurrent.futures
    command = 'echo run >> count.txt; sleep .2; printf complete'
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        responses = list(pool.map(lambda _: submit(bridge_server, command), range(8)))
    assert all(r.status == 202 for r in responses)
    assert len({r.body['job_id'] for r in responses}) == 1
    finished(bridge_server, responses[0].body['job_id'])
    assert (bridge_server.home / 'closepaw/workspace/count.txt').read_text() == 'run\n'


def test_job_queue_is_bounded_and_cancel_can_skip_queued_job(bridge_server):
    responses = [bridge_server.post_json('/v1/jobs', {'request_id': str(i), 'command': 'sleep 20'}) for i in range(8)]
    assert all(r.status == 202 for r in responses)
    assert bridge_server.post_json('/v1/jobs', {'request_id': 'full', 'command': 'true'}).status == 429
    last_id = responses[-1].body['job_id']
    bridge_server.post_json('/v1/jobs/' + last_id + '/cancel', {})
    assert finished(bridge_server, last_id)['state'] == 'cancelled'
    for r in responses:
        bridge_server.post_json('/v1/jobs/' + r.body['job_id'] + '/cancel', {})


def test_unknown_job_and_invalid_paging(bridge_server):
    assert bridge_server.get_json('/v1/jobs/missing').status == 404
    response = submit(bridge_server)
    for query in ('limit=0', 'limit=65537', 'stdout_offset=-1', 'limit=no', 'bad=1', 'limit=1&limit=2'):
        assert bridge_server.get_json('/v1/jobs/' + response.body['job_id'] + '?' + query).status == 400


def test_nonzero_job_and_stderr(bridge_server):
    response = submit(bridge_server, 'printf error >&2; exit 7')
    result = finished(bridge_server, response.body['job_id'])
    assert result['state'] == 'failed'
    assert result['stderr'] == 'error'
    assert result['exit_code'] == 7


def test_standalone_bundle_can_run_jobs(tmp_path, free_port):
    import os
    import subprocess
    import sys
    from pathlib import Path
    from conftest import AUTH_TOKEN, BridgeProcess, wait_for_health
    root = Path(__file__).resolve().parents[3]
    destination = tmp_path / 'standalone.py'
    subprocess.run([sys.executable, str(root / 'scripts/bundle-termux-bridge.py'), str(destination)], check=True)
    assert 'from closepaw_jobs import' not in destination.read_text()
    environment = dict(os.environ, HOME=str(tmp_path), CLOSEPAW_BRIDGE_TOKEN=AUTH_TOKEN)
    process = subprocess.Popen([sys.executable, str(destination), '--port', str(free_port), '--idle-timeout-sec', '0'],
                               env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    bridge = BridgeProcess(free_port, tmp_path, process)
    try:
        wait_for_health(bridge)
        response = submit(bridge, 'printf bundled', timeout_ms=3600000)
        assert response.status == 202
        assert finished(bridge, response.body['job_id'])['stdout'] == 'bundled'
    finally:
        stop_process(process)


def test_output_limit_and_offsets_count_original_utf8_bytes(bridge_server):
    response = submit(bridge_server, "printf '가나'", max_output_bytes=4)
    result = finished(bridge_server, response.body['job_id'])
    assert result['stdout_truncated'] is True
    assert result['stdout_next_offset'] == 4


def test_jobs_readiness_is_authenticated_and_advertised(bridge_server):
    assert bridge_server.get_json('/v1/health').body['job_api_version'] == 1
    assert request_json(bridge_server.port, 'GET', '/v1/jobs', auth_token=None).status == 401
    assert request_json(bridge_server.port, 'GET', '/v1/jobs', auth_token='wrong').status == 401
    assert bridge_server.get_json('/v1/jobs').body == {'status': 'ready', 'job_api_version': 1}


def test_corrupt_history_fails_closed_without_reexecution(bridge_factory, free_port, tmp_path):
    directory = tmp_path / '.closepaw/jobs'
    directory.mkdir(parents=True)
    (directory / 'broken.json').write_text('{broken')
    bridge = bridge_factory(free_port, tmp_path)
    response = submit(bridge, 'echo should-not-run > executed')
    assert response.status == 503
    assert response.body['error'] == 'job_store_unhealthy'
    assert bridge.get_json('/v1/jobs').status == 503
    assert not (tmp_path / 'closepaw/workspace/executed').exists()


def test_korean_pages_preserve_characters_with_byte_offsets(bridge_server):
    response = submit(bridge_server, "printf '가나다라마바사'", max_output_bytes=100)
    job_id = response.body['job_id']
    finished(bridge_server, job_id)
    for limit in (1, 2, 4, 7):
        offset = 0
        pages = []
        while offset < 21:
            result = bridge_server.get_json('/v1/jobs/' + job_id + '?limit=' + str(limit) + '&stdout_offset=' + str(offset)).body
            pages.append(result['stdout'])
            assert result['stdout_next_offset'] > offset
            offset = result['stdout_next_offset']
        assert ''.join(pages) == '가나다라마바사'
