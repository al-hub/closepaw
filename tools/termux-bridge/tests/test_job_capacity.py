"""Capacity regression against the production daemon, runnable without pytest."""
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from closepaw_jobs import JobError, JobManager

TOKEN = 'capacity-test-token-012345678901234567890'
OWNER = hashlib.sha256(TOKEN.encode()).hexdigest()


def seed(directory, count=1000, state='succeeded', unknown=False, start=0):
    directory.mkdir(parents=True, exist_ok=True)
    for index in range(start, start + count):
        job_id = f'{index:032x}'
        record = dict(job_id=job_id, request_id=f'seed-{index}', owner=OWNER,
                      fingerprint='0' * 64, state=state, stdout=f'retained-{index}', stderr='',
                      stdout_truncated=False, stderr_truncated=False, exit_code=0,
                      outcome_unknown=unknown, duration_ms=1)
        (directory / (job_id + '.json')).write_text(json.dumps(record))


class JobCapacityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.directory = self.root / 'jobs'

    def tearDown(self):
        self.temporary.cleanup()

    def manager(self):
        return JobManager(self.directory, TOKEN, threading.Lock(),
                          lambda p: dict(command=p['command'], cwd=str(self.root),
                                         env=dict(os.environ), max_bytes=65536),
                          ValueError('invalid'),
                          lambda proc, graceful=True: os.killpg(proc.pid, signal.SIGKILL))

    def finish(self, manager, job_id):
        for _ in range(300):
            result = manager.snapshot(job_id)
            if result['state'] not in ('queued', 'running') and job_id not in manager.processes:
                return result
            time.sleep(.01)
        self.fail('command did not finish')

    def test_full_store_preserves_results_and_dedup_across_restart(self):
        seed(self.directory)
        original_bytes = (self.directory / ('0' * 32 + '.json')).read_bytes()
        manager = self.manager()
        payload = dict(request_id='new-command', command="printf run >> marker; printf 'capacity-ok'")
        result = self.finish(manager, manager.submit(payload)['job_id'])
        self.assertEqual((result['exit_code'], result['stdout']), (0, 'capacity-ok'))
        self.assertEqual((self.root / 'marker').read_text(), 'run')
        self.assertEqual(manager.snapshot('0' * 32)['stdout'], 'retained-0')
        self.assertEqual((manager.archive / ('0' * 32 + '.json')).read_bytes(), original_bytes)
        restarted = self.manager()
        self.assertEqual(restarted.submit(payload)['job_id'], result['job_id'])
        self.assertEqual((self.root / 'marker').read_text(), 'run')
        self.assertEqual(restarted.snapshot('0' * 32)['stdout'], 'retained-0')
        self.assertEqual(len(list(self.directory.glob('*.json'))), 1000)

    def test_running_and_unknown_results_are_never_archived(self):
        seed(self.directory)
        manager = self.manager()
        for index, (state, unknown) in enumerate((('running', False), ('interrupted', True))):
            record = manager.jobs[f'{index:032x}']
            record.update(state=state, outcome_unknown=unknown)
            manager._persist(record)
        result = self.finish(manager, manager.submit(dict(request_id='new', command='printf ok'))['job_id'])
        self.assertEqual(result['stdout'], 'ok')
        for index in (0, 1):
            self.assertTrue((self.directory / (f'{index:032x}' + '.json')).exists())
            self.assertNotIn(f'{index:032x}', manager.archived)
        self.assertIn(f'{2:032x}', manager.archived)

    def test_no_confirmed_results_or_full_archive_refuses_without_execution(self):
        seed(self.directory, state='interrupted', unknown=True)
        manager = self.manager()
        with self.assertRaises(JobError) as raised:
            manager.submit(dict(request_id='blocked', command='touch should-not-exist'))
        self.assertEqual(raised.exception.error, 'job_store_full_no_confirmed_results')
        self.assertFalse((self.root / 'should-not-exist').exists())
        seed(manager.archive, start=2000)
        seed(self.directory)
        manager = self.manager()
        with self.assertRaises(JobError) as raised:
            manager.submit(dict(request_id='blocked', command='touch should-not-exist'))
        self.assertEqual(raised.exception.error, 'job_archive_full')
        self.assertFalse((self.root / 'should-not-exist').exists())
        self.assertEqual(len(manager.jobs), 2000)

    def test_concurrent_capacity_reclaim_and_duplicate_submit_runs_once(self):
        from concurrent.futures import ThreadPoolExecutor
        seed(self.directory)
        manager = self.manager()
        payload = dict(request_id='once', command='printf run >> marker; printf done')
        with ThreadPoolExecutor(max_workers=8) as pool:
            replies = list(pool.map(lambda _: manager.submit(payload), range(8)))
        self.assertEqual(len({r['job_id'] for r in replies}), 1)
        self.finish(manager, replies[0]['job_id'])
        self.assertEqual((self.root / 'marker').read_text(), 'run')
        self.assertEqual(len(list(manager.archive.glob('*.json'))), 1)

    def test_archived_request_still_deduplicates_and_pages_after_restart(self):
        from unittest.mock import patch
        manager = self.manager()
        old = dict(request_id='old', command="printf run >> marker; printf 'archived-output'")
        old_id = self.finish(manager, manager.submit(old)['job_id'])['job_id']
        with patch('closepaw_jobs.MAX_JOBS', 1):
            self.finish(manager, manager.submit(dict(request_id='new', command='printf new'))['job_id'])
        self.assertIn(old_id, manager.archived)
        restarted = self.manager()
        self.assertEqual(restarted.submit(old)['job_id'], old_id)
        self.assertEqual(restarted.snapshot(old_id, stdout_offset=9)['stdout'], 'output')
        self.assertEqual((self.root / 'marker').read_text(), 'run')

    def test_archive_write_failure_preserves_history_and_refuses_new_execution(self):
        from unittest.mock import patch
        seed(self.directory)
        manager = self.manager()
        with patch('closepaw_jobs.os.replace', side_effect=OSError('storage unavailable')):
            with self.assertRaises(JobError) as raised:
                manager.submit(dict(request_id='blocked', command='touch should-not-exist'))
        self.assertEqual(raised.exception.error, 'job_archive_unavailable')
        self.assertEqual(len(list(self.directory.glob('*.json'))), 1000)
        self.assertFalse((self.root / 'should-not-exist').exists())
        with self.assertRaises(JobError):
            manager.readiness()

    def test_corrupt_or_duplicate_archived_history_fails_closed(self):
        seed(self.directory, count=1)
        archive = self.directory / 'archive'
        seed(archive, count=1)
        manager = self.manager()
        with self.assertRaises(JobError):
            manager.submit(dict(request_id='blocked', command='touch should-not-exist'))
        self.assertFalse((self.root / 'should-not-exist').exists())

    def test_full_production_http_server_executes_and_returns_output(self):
        seed(self.root / '.closepaw/jobs')
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
        script = Path(__file__).resolve().parents[1] / 'closepaw_bridge.py'
        process = subprocess.Popen([sys.executable, str(script), '--port', str(port), '--idle-timeout-sec', '0'],
                                   env=dict(os.environ, HOME=str(self.root), CLOSEPAW_BRIDGE_TOKEN=TOKEN),
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        base = f'http://127.0.0.1:{port}'
        def request(path, payload=None):
            data = None if payload is None else json.dumps(payload).encode()
            req = urllib.request.Request(base + path, data=data, headers={
                'X-ClosePaw-Token': TOKEN, 'Content-Type': 'application/json'})
            with urllib.request.urlopen(req, timeout=5) as response:
                return json.load(response)
        try:
            for _ in range(200):
                try:
                    request('/v1/jobs')
                    break
                except (OSError, urllib.error.URLError):
                    time.sleep(.02)
            payload = dict(request_id='http-capacity', command="printf run >> marker; printf 'http-capacity-ok'")
            job_id = request('/v1/jobs', payload)['job_id']
            for _ in range(200):
                result = request('/v1/jobs/' + job_id)
                if result['state'] not in ('running', 'queued'):
                    break
                time.sleep(.02)
            self.assertEqual((result['exit_code'], result['stdout']), (0, 'http-capacity-ok'))
            self.assertEqual(request('/v1/jobs', payload)['job_id'], job_id)
            self.assertEqual((self.root / 'closepaw/workspace/marker').read_text(), 'run')
            self.assertEqual(request('/v1/jobs/' + '0' * 32)['stdout'], 'retained-0')
        finally:
            process.terminate()
            process.wait(timeout=5)


if __name__ == '__main__':
    unittest.main()
