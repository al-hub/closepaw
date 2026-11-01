"""Independent, operator-pinned maintenance. No jobs submission is used here.

Pins are a local operator trust decision, NOT a downloaded checksum. Provision via
--approve-update COMMIT JOBS_SHA BRIDGE_SHA MAINTENANCE_SHA after review. The APK's
embedded source and bootstrap are authenticated by the existing APK signing key.
"""
import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import urllib.error
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hmac

MAINTENANCE_FILES = ('closepaw_jobs.py', 'closepaw_bridge.py', 'closepaw_maintenance.py')


class MaintenanceError(Exception):
    pass


def fsync_directory(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def atomic_write(path, data):
    path = Path(path)
    fd, name = tempfile.mkstemp(prefix='.' + path.name, dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
        fsync_directory(path.parent)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def approve_pin(root, revision, hashes):
    if not re.fullmatch('[a-f0-9]{40}', revision) or len(hashes) != 3 or any(
            not re.fullmatch('[a-f0-9]{64}', value) for value in hashes):
        raise MaintenanceError('invalid_pin')
    directory = root / 'approved-updates'
    directory.mkdir(mode=0o700, exist_ok=True)
    atomic_write(directory / (revision + '.json'), json.dumps(dict(zip(MAINTENANCE_FILES, hashes))).encode())
    atomic_write(root / 'requested-update.json', json.dumps({'revision': revision}).encode())


def load_pin(root, revision):
    if not isinstance(revision, str) or not re.fullmatch('[a-f0-9]{40}', revision):
        raise MaintenanceError('unapproved_revision')
    try:
        path = root / 'approved-updates' / (revision + '.json')
        if path.stat().st_size > 1024:
            raise ValueError()
        pin = json.loads(path.read_text())
        if not isinstance(pin, dict) or set(pin) != set(MAINTENANCE_FILES) or any(
                not isinstance(v, str) or not re.fullmatch('[a-f0-9]{64}', v) for v in pin.values()):
            raise ValueError()
        return pin
    except (OSError, ValueError, TypeError):
        raise MaintenanceError('unapproved_revision') from None


def validate_source(data, digest):
    if len(data) > 2 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != digest:
        raise MaintenanceError('integrity_failed')
    try:
        compile(data, '<approved-source>', 'exec')
    except (SyntaxError, ValueError, UnicodeError):
        raise MaintenanceError('syntax_failed') from None
    return data


class MaintenanceNoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def download_bundle(root, revision):
    pin = load_pin(root, revision)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), MaintenanceNoRedirect())
    sources = {}
    for name in MAINTENANCE_FILES:
        url = 'https://raw.githubusercontent.com/al-hub/closepaw/' + revision + '/tools/termux-bridge/' + name
        try:
            with opener.open(url, timeout=10) as response:
                data = response.read(2 * 1024 * 1024 + 1)
        except (OSError, urllib.error.URLError):
            raise MaintenanceError('download_failed') from None
        sources[name] = validate_source(data, pin[name]).decode('utf-8')
    bridge = sources['closepaw_bridge.py'].replace('from closepaw_jobs import JobManager, JobError\n', '').replace(
        'from closepaw_maintenance import MaintenanceController, MaintenanceError, approve_pin\n', '')
    bundle = (sources['closepaw_jobs.py'] + '\n' + sources['closepaw_maintenance.py'] + '\n' + bridge).encode()
    try:
        compile(bundle, '<approved-bundle>', 'exec')
    except (SyntaxError, ValueError):
        raise MaintenanceError('syntax_failed') from None
    return bundle


class MaintenanceController:
    def __init__(self, root, script, port, token, event, interval=10):
        self.root, self.script, self.port, self.token = Path(root), Path(script), port, token
        self.event = event
        self.lock = threading.RLock()
        self.child = None
        self.server = None
        self.interval = interval
        self.state_path = self.root / 'maintenance-state.json'
        self.journal = self.root / 'maintenance-transaction.json'
        self.backup = self.root / 'bridge.previous.py'
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), MaintenanceNoRedirect())
        self.state = {'status': 'idle', 'attempts': 0, 'verification': 'not_run'}
        if self.state_path.exists():
            try:
                if self.state_path.stat().st_size > 8192:
                    raise ValueError()
                self.state = json.loads(self.state_path.read_text())
                if (not isinstance(self.state, dict) or
                        type(self.state.get('attempts')) is not int or not 0 <= self.state['attempts'] <= 3 or
                        not isinstance(self.state.get('status'), str)):
                    raise ValueError()
            except (OSError, ValueError):
                self.state = {'status': 'state_corrupt', 'attempts': 3, 'verification': 'not_run'}

    def record(self, status, **fields):
        self.state.update(status=status, **fields)
        atomic_write(self.state_path, json.dumps(self.state).encode())
        self.event('maintenance_' + status)  # no source, credentials, command or output
        return dict(self.state)

    def call(self, action, payload=None):
        request = urllib.request.Request(f'http://127.0.0.1:{self.port}/v1/maintenance/' + action,
            data=json.dumps(payload or {}).encode(), headers={'X-ClosePaw-Token': self.token,
            'Content-Type': 'application/json'})
        try:
            with self.opener.open(request, timeout=3) as response:
                result = json.loads(response.read(8192))
                if not isinstance(result, dict):
                    raise ValueError()
                return result
        except urllib.error.HTTPError as exc:
            # Only errors from our fixed protocol are eligible for diagnostics.
            try:
                reason = json.loads(exc.read(4096)).get('error')
            except (ValueError, AttributeError):
                reason = None
            allowed = {'active_jobs', 'job_store_unhealthy', 'outcome_unknown', 'not_drained',
                'job_archive_full', 'job_archive_conflict', 'job_archive_unavailable',
                'job_store_full_no_confirmed_results', 'job_queue_full'}
            raise MaintenanceError('auth_mismatch' if exc.code == 401 else
                reason if reason in allowed else 'legacy_or_port_conflict') from None
        except urllib.error.URLError as exc:
            raise MaintenanceError('daemon_absent' if isinstance(exc.reason, ConnectionRefusedError) else
                'response_timeout' if isinstance(exc.reason, (TimeoutError, socket.timeout)) else 'bridge_unavailable') from None
        except (TimeoutError, socket.timeout):
            raise MaintenanceError('response_timeout') from None
        except (OSError, ValueError):
            raise MaintenanceError('bridge_unavailable') from None

    def launch(self, drained=False):
        env = dict(os.environ, CLOSEPAW_BRIDGE_TOKEN=self.token, CLOSEPAW_START_DRAINED='1' if drained else '0')
        self.child = subprocess.Popen([sys.executable, str(self.script), '--port', str(self.port),
            '--idle-timeout-sec', '0'], env=env, stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return self.child

    def wait_ready(self, expected=None):
        for _ in range(40):
            try:
                status = self.call('status')
                if expected is not None and status.get('source_sha256') != expected:
                    raise MaintenanceError('revision_mismatch')
                return status
            except MaintenanceError:
                if self.child is not None and self.child.poll() is not None:
                    break
                time.sleep(.1)
        raise MaintenanceError('startup_failed')

    def stop_drained(self):
        self.call('stop')
        for _ in range(40):
            try:
                self.call('status')
            except MaintenanceError as exc:
                if str(exc) == 'daemon_absent':
                    if self.child is not None:
                        self.child.wait(timeout=3)
                    return
                if str(exc) not in ('bridge_unavailable', 'response_timeout'):
                    raise
            time.sleep(.1)
        raise MaintenanceError('stop_failed')

    def verify(self, runtime_only=False):
        result = self.call('verify_runtime' if runtime_only else 'verify')
        if result.get('exit_code') != 0 or result.get('stdout') != 'CLOSEPAW_MAINTENANCE_OK':
            raise MaintenanceError('execution_verification_failed')
        return {'verification': 'runtime_only_exit0' if runtime_only else 'jobs_exit0_stdout_match', 'exit_code': 0, 'stdout': 'CLOSEPAW_MAINTENANCE_OK'}

    def apply(self, revision):
        # Independent of jobs; only this process holds supervisor.lock. Requests
        # have no automatic replay of user commands, only fixed maintenance actions.
        if not self.lock.acquire(blocking=False):
            return {'status': 'recovery_busy'}
        prepared = False
        preparation_started = False
        activation_started = False
        try:
            if self.state.get('status') == 'state_corrupt':
                return dict(self.state)
            load_pin(self.root, revision)
            if self.journal.exists():
                return self.record('interrupted_update_user_required')
            attempts = self.state.get('attempts', 0) if self.state.get('revision') == revision else 0
            if attempts >= 3:
                return self.record('retry_exhausted')
            preparation_started = True
            self.call('prepare')
            prepared = True
            self.record('downloading', revision=revision, attempts=attempts + 1, verification='not_run')
            candidate = download_bundle(self.root, revision)
            digest = hashlib.sha256(candidate).hexdigest()
            # prepare closes admission under the same lock as submit; queued,
            # running, unknown outcomes and corrupt history all refuse replacement.
            original = self.script.read_bytes()
            atomic_write(self.backup, original)
            atomic_write(self.journal, json.dumps({'candidate': digest,
                'previous': hashlib.sha256(original).hexdigest()}).encode())
            self.stop_drained()
            try:
                atomic_write(self.script, candidate)
                self.launch(drained=True)
                self.wait_ready(digest)
                proof = self.verify()
                atomic_write(self.journal, json.dumps({'candidate': digest, 'phase': 'activating'}).encode())
                activation_started = True
                self.call('activate')
                self.journal.unlink()
                fsync_directory(self.root)
                return self.record('updated', source_sha256=digest, **proof)
            except (MaintenanceError, OSError, subprocess.TimeoutExpired):
                if activation_started:
                    return self.record('activation_outcome_unknown', verification='recheck_required')
                # Candidate is owned and admission has never opened. It cannot
                # contain user jobs; rollback never kills an adopted user daemon.
                if self.child is not None and self.child.poll() is None:
                    self.child.terminate()
                    try:
                        self.child.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        self.child.kill()
                        self.child.wait(timeout=3)
                atomic_write(self.script, original)
                self.launch(drained=True)
                self.wait_ready(hashlib.sha256(original).hexdigest())
                proof = self.verify(runtime_only=True)
                atomic_write(self.journal, json.dumps({'candidate': hashlib.sha256(original).hexdigest(), 'phase': 'activating_rollback'}).encode())
                activation_started = True
                self.call('activate')
                self.journal.unlink()
                fsync_directory(self.root)
                return self.record('rolled_back', **proof)
        except MaintenanceError as exc:
            if preparation_started and not self.journal.exists():
                try:
                    if self.call('status').get('drained'):
                        self.call('activate')
                except MaintenanceError:
                    pass
            return self.record(str(exc))
        except (OSError, ValueError, subprocess.TimeoutExpired):
            if preparation_started and not self.journal.exists():
                try:
                    if self.call('status').get('drained'):
                        self.call('activate')
                except MaintenanceError:
                    pass
            return self.record('replacement_or_rollback_failed')
        finally:
            self.lock.release()

    def requested_revision(self):
        try:
            revision = json.loads((self.root / 'requested-update.json').read_text())['revision']
            load_pin(self.root, revision)
            if self.state.get('revision') == revision and self.state.get('status') in {
                    'updated', 'rolled_back', 'retry_exhausted', 'interrupted_update_user_required', 'activation_outcome_unknown'}:
                return None
            return revision
        except (OSError, ValueError, KeyError, TypeError, MaintenanceError):
            return None

    def resume(self):
        """Fail closed across power/process interruption; never guess a job result."""
        if self.journal.exists():
            self.record('interrupted_update_user_required')
            return False
        return True

    def observation(self):
        result = dict(self.state, supervisor='running', automatic_after_termux_force_stop=False,
            boot_hook_present=(self.root.parent / '.termux/boot/10-closepaw-bridge').is_file())
        # Historical transaction proof is separate from fresh authenticated runtime facts.
        result['last_verification'] = result.pop('verification', 'not_run')
        result['verification'] = 'not_currently_verified'
        try:
            live = self.call('status')
            result['daemon_source_sha256'] = live['source_sha256']
            result['runtime_revision'] = live['runtime_revision']
            result['daemon_state'] = 'drained' if live['drained'] else 'responding'
            if result.get('source_sha256') and live['source_sha256'] != result['source_sha256']:
                result['daemon_state'] = 'revision_mismatch'
        except MaintenanceError as exc:
            result['daemon_state'] = str(exc)
        except KeyError:
            result['daemon_state'] = 'bridge_unavailable'
        return result

    def serve(self, port):
        controller = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_GET(self): self.handle_request(False)
            def do_POST(self): self.handle_request(True)
            def handle_request(self, write):
                self.connection.settimeout(5)
                token = self.headers.get('X-ClosePaw-Token', '')
                code, reply = 200, {}
                if not token or not hmac.compare_digest(token, controller.token):
                    code, reply = 401, {'error': 'unauthorized'}
                elif not write and self.path == '/v1/maintenance/status':
                    reply = controller.observation()
                elif write and self.path == '/v1/maintenance/update':
                    try:
                        size = int(self.headers.get('Content-Length', '0'))
                        if not 0 < size <= 256: raise ValueError()
                        payload = json.loads(self.rfile.read(size))
                        if set(payload) != {'revision'}: raise ValueError()
                        reply = controller.apply(payload['revision'])
                    except (ValueError, TypeError):
                        code, reply = 400, {'error': 'invalid_request'}
                else:
                    code, reply = 404, {'error': 'not_found'}
                body = json.dumps(reply).encode()
                self.send_response(code)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        self.server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
