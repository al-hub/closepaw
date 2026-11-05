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
        payload = dict(request_id='post-capacity', command="printf 'cold-ok'")
        result = self.finish(manager, manager.submit(payload)['job_id'])
        self.assertEqual((result['exit_code'], result['stdout']), (0, 'cold-ok'))
        self.assertEqual(len(manager.jobs), 2000)
        self.assertEqual(manager.snapshot('0' * 32)['stdout'], 'retained-0')
        self.assertTrue((self.directory / 'cold-records.sqlite3').exists())
        restarted = self.manager()
        self.assertEqual(restarted.submit(payload)['job_id'], result['job_id'])
        self.assertEqual(restarted.snapshot('0' * 32)['stdout'], 'retained-0')

    def test_post_start_cold_tampering_fails_closed_for_reads_and_replays(self):
        seed(self.directory)
        seed(self.directory / 'archive', start=2000)
        manager = self.manager()
        manager.submit(dict(request_id='spilling', command='printf safe'))
        with __import__('sqlite3').connect(self.directory / 'cold-records.sqlite3') as db:
            db.execute("UPDATE records SET sha256=? WHERE job_id=?", ('f' * 64,'0' * 32))
        with self.assertRaises(JobError) as rejected:
            manager.snapshot('0' * 32)
        self.assertEqual(rejected.exception.error, 'job_store_unhealthy')
        with self.assertRaises(JobError):
            manager.submit(dict(request_id='never-run', command='touch should-not-exist'))
        self.assertFalse((self.root / 'should-not-exist').exists())

    def test_noncanonical_existing_sqlite_page_size_is_rejected(self):
        self.directory.mkdir(parents=True)
        import sqlite3
        db_path = self.directory / 'cold-records.sqlite3'
        with sqlite3.connect(db_path) as db:
            db.execute('PRAGMA page_size=8192')
            db.execute('CREATE TABLE dummy(x)')
        manager = self.manager()
        self.assertFalse(manager.store_healthy)
        with self.assertRaises(JobError) as raised:
            manager.submit(dict(request_id='blocked', command='touch should-not-exist'))
        self.assertEqual(raised.exception.error, 'job_store_unhealthy')
        self.assertFalse((self.root / 'should-not-exist').exists())

    def test_terminal_cold_result_cancel_query_is_idempotent(self):
        seed(self.directory)
        seed(self.directory / 'archive', start=2000)
        manager = self.manager()
        self.finish(manager, manager.submit(
            dict(request_id='trigger-spill', command='printf ok')
        )['job_id'])
        old_id = '0' * 32
        self.assertEqual(manager.snapshot(old_id)['state'], 'succeeded')
        self.assertEqual(manager.cancel(old_id)['state'], 'succeeded')
        self.assertEqual(self.manager().cancel(old_id)['stdout'], 'retained-0')

    def test_crash_after_cold_commit_before_hot_unlink_preserves_identity(self):
        seed(self.directory)
        seed(self.directory / 'archive', start=2000)
        manager = self.manager()
        old_id = '0' * 32
        original = (self.directory / (old_id + '.json')).read_bytes()
        # Simulate a crash at exactly the commit/unlink boundary.
        manager.cold.put(original)
        rebooted = self.manager()
        self.assertTrue(rebooted.store_healthy)
        self.assertEqual(rebooted.snapshot(old_id)['stdout'], 'retained-0')
        self.finish(rebooted, rebooted.submit(
            dict(request_id='recovered', command='printf recovery-ok')
        )['job_id'])
        self.assertFalse((self.directory / (old_id + '.json')).exists())
        self.assertEqual(self.manager().snapshot(old_id)['stdout'], 'retained-0')

    def test_divergent_hot_and_cold_duplicate_blocks_admission(self):
        seed(self.directory)
        seed(self.directory / 'archive', start=2000)
        manager = self.manager()
        path = self.directory / ('0' * 32 + '.json')
        raw = path.read_bytes()
        manager.cold.put(raw)
        path.write_text(json.dumps(json.loads(raw), indent=2))
        rebooted = self.manager()
        self.assertFalse(rebooted.store_healthy)
        with self.assertRaises(JobError) as raised:
            rebooted.submit(dict(request_id='never-execute', command='touch should-not-exist'))
        self.assertEqual(raised.exception.error, 'job_store_unhealthy')
        self.assertFalse((self.root / 'should-not-exist').exists())

    def test_cold_archive_preserves_original_bytes_and_dedup_after_restart(self):
        seed(self.directory)
        archive = self.directory / 'archive'
        seed(archive, start=2000)
        source = self.directory / ('0' * 32 + '.json')
        original = source.read_bytes()
        manager = self.manager()
        first = self.finish(manager, manager.submit(dict(
            request_id='new-after-full', command="printf 'new-ok'"))['job_id'])
        self.assertFalse(source.exists())
        with __import__('sqlite3').connect(self.directory / 'cold-records.sqlite3') as db:
            stored = db.execute('SELECT payload FROM records WHERE job_id=?', ('0' * 32,)).fetchone()
        self.assertEqual(stored[0], original)
        self.assertEqual(manager.snapshot('0' * 32)['stdout'], 'retained-0')
        restarted = self.manager()
        self.assertEqual(restarted.snapshot('0' * 32)['stdout'], 'retained-0')
        self.assertEqual(restarted.submit(dict(
            request_id='new-after-full', command="printf 'new-ok'"))['job_id'], first['job_id'])

    def test_malicious_same_request_with_distinct_job_id_blocks_admission(self):
        seed(self.directory)
        seed(self.directory / 'archive', start=2000)
        manager = self.manager()
        self.finish(manager, manager.submit(dict(request_id='cold-first', command='printf ok'))['job_id'])
        import sqlite3
        with sqlite3.connect(self.directory / 'cold-records.sqlite3') as db:
            raw = db.execute('SELECT payload FROM records LIMIT 1').fetchone()[0]
        self.assertTrue(raw)
        # The DB keeps its own request ID namespace; corrupt conflicts are
        # reported on restart rather than executing a second command.
        duplicated = json.loads(raw)
        for path in self.directory.glob('*.json'):
            sample = json.loads(path.read_text())
            if sample['job_id'] != duplicated['job_id']:
                sample['request_id'] = duplicated['request_id']
                path.write_text(json.dumps(sample))
                break
        restarted = self.manager()
        with self.assertRaises(JobError) as raised:
            restarted.submit(dict(request_id='should-not-run',command='touch should-not-exist'))
        self.assertEqual(raised.exception.error, 'job_store_unhealthy')
        self.assertFalse((self.root / 'should-not-exist').exists())

    def test_corrupt_cold_archive_refuses_admission_without_erasure(self):
        seed(self.directory)
        seed(self.directory / 'archive', start=2000)
        manager = self.manager()
        self.finish(manager, manager.submit(dict(request_id='fill', command='printf done'))['job_id'])
        db_path = self.directory / 'cold-records.sqlite3'
        import sqlite3
        with sqlite3.connect(db_path) as db:
            db.execute("UPDATE records SET sha256=? WHERE job_id=?", ('f' * 64,'0' * 32))
        restarted = self.manager()
        with self.assertRaises(JobError) as raised:
            restarted.submit(dict(request_id='blocked', command='touch should-not-exist'))
        self.assertEqual(raised.exception.error, 'job_store_unhealthy')
        self.assertFalse((self.root / 'should-not-exist').exists())

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
        # Full 1000 hot + 1000 legacy archive capacity, as seen by the real
        # authenticated HTTP server, not just a mocked JobManager.
        seed(self.root / '.closepaw/jobs')
        seed(self.root / '.closepaw/jobs/archive', start=2000)
        original = (self.root / '.closepaw/jobs' / ('0' * 32 + '.json')).read_bytes()
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
            self.assertEqual(request('/v1/jobs/' + f'{2000:032x}')['stdout'], 'retained-2000')
            cold = self.root / '.closepaw/jobs/cold-records.sqlite3'
            self.assertTrue(cold.is_file())
            import sqlite3
            with sqlite3.connect(cold) as db:
                row = db.execute('SELECT payload FROM records WHERE job_id=?', ('0' * 32,)).fetchone()
            self.assertIsNotNone(row)
            self.assertEqual(row[0], original)
            self.assertEqual(len(list((self.root / '.closepaw/jobs/archive').glob('*.json'))), 1000)
        finally:
            process.terminate()
            process.wait(timeout=5)


if __name__ == '__main__':
    unittest.main()
