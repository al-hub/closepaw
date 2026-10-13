"""Persistent, bounded asynchronous commands independent of HTTP connections."""
import base64
import hashlib
import json
import os
import re
import subprocess
import threading
import time
import uuid
from pathlib import Path

TERMINAL = {'succeeded', 'failed', 'timed_out', 'cancelled', 'interrupted'}
MAX_JOBS = 1000
MAX_ACTIVE = 8
JOB_MAX_TIMEOUT_MS = 3600000
MAX_OUTPUT_BYTES = 1048576


class JobError(Exception):
    def __init__(self, status, error):
        self.status, self.error = status, error


class JobManager:
    def __init__(self, directory, token, exec_lock, parse_exec, invalid_request, kill_group):
        self.directory = Path(directory)
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.owner = hashlib.sha256(token.encode()).hexdigest()
        self.exec_lock, self.parse_exec = exec_lock, parse_exec
        self.invalid_request, self.kill_group = invalid_request, kill_group
        self.lock = threading.RLock()
        self.jobs, self.processes, self.cancel_requests = {}, {}, set()
        self.store_healthy = True
        for path in self.directory.glob('*.json'):
            try:
                job = json.loads(path.read_text())
                if not isinstance(job, dict) or not isinstance(job.get('owner'), str):
                    raise ValueError('invalid record')
                if job.get('owner') != self.owner:
                    continue
                required = {'job_id', 'request_id', 'fingerprint', 'state', 'stdout', 'stderr',
                            'stdout_truncated', 'stderr_truncated', 'exit_code', 'outcome_unknown', 'duration_ms'}
                if (not required <= job.keys() or job['job_id'] != path.stem or
                        not re.fullmatch(r'[a-f0-9]{32}', job['job_id']) or
                        job['state'] not in TERMINAL | {'queued', 'running'} or
                        not isinstance(job['request_id'], str) or not isinstance(job['fingerprint'], str)):
                    raise ValueError('invalid record')
                if job['state'] not in TERMINAL:
                    job.update(state='interrupted', outcome_unknown=True, exit_code=None)
                    self._persist(job)
                self.jobs[job['job_id']] = job
            except (OSError, ValueError, KeyError, TypeError):
                # Lost history can hide a prior side effect; never accept new
                # commands until the persisted store has been repaired.
                self.store_healthy = False

    def _persist(self, job):
        destination = self.directory / (job['job_id'] + '.json')
        temporary = destination.with_suffix('.tmp')
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, 'w') as handle:
            json.dump(job, handle, separators=(',', ':'))
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, destination)

    def readiness(self):
        with self.lock:
            if not self.store_healthy:
                raise JobError(503, "job_store_unhealthy")
            return {"status": "ready", "job_api_version": 1}

    def submit(self, payload):
        self.readiness()
        allowed = {'request_id', 'command', 'cwd', 'env', 'timeout_ms', 'max_output_bytes'}
        request_id = payload.get('request_id')
        command = payload.get('command')
        timeout = payload.get('timeout_ms', 120000)
        output_limit = payload.get('max_output_bytes', 65536)
        if (set(payload) - allowed or not isinstance(request_id, str) or
                not re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}', request_id) or
                not isinstance(command, str) or not command.strip() or '\x00' in command or
                len(command.encode()) > 65536 or type(timeout) is not int or
                not 1 <= timeout <= JOB_MAX_TIMEOUT_MS or type(output_limit) is not int or
                not 1 <= output_limit <= MAX_OUTPUT_BYTES):
            raise self.invalid_request
        env = payload.get('env')
        if env is not None and (not isinstance(env, dict) or any(
                not isinstance(k, str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', k) or
                not isinstance(v, str) or '\x00' in v for k, v in env.items())):
            raise self.invalid_request
        spec = self.parse_exec(payload)
        spec['timeout_ms'] = timeout
        fingerprint = hashlib.sha256(json.dumps(dict(payload, cwd=spec['cwd'], timeout_ms=timeout,
                        max_output_bytes=output_limit), sort_keys=True).encode()).hexdigest()
        with self.lock:
            for job in self.jobs.values():
                if job['request_id'] == request_id:
                    if job['fingerprint'] != fingerprint:
                        raise JobError(409, 'request_id_conflict')
                    return self.snapshot(job['job_id'])
            if len(list(self.directory.glob('*.json'))) >= MAX_JOBS:
                raise JobError(429, 'job_store_full')
            if sum(j['state'] not in TERMINAL for j in self.jobs.values()) >= MAX_ACTIVE:
                raise JobError(429, 'job_queue_full')
            job_id = uuid.uuid4().hex
            job = dict(job_id=job_id, request_id=request_id, owner=self.owner, fingerprint=fingerprint,
                       state='queued', stdout='', stderr='', stdout_truncated=False,
                       stderr_truncated=False, exit_code=None, outcome_unknown=False, duration_ms=0)
            self._persist(job)
            self.jobs[job_id] = job
            threading.Thread(target=self._run, args=(job_id, spec), daemon=True).start()
            return self.snapshot(job_id)

    def snapshot(self, job_id, stdout_offset=0, stderr_offset=0, limit=65536):
        if any(type(v) is not int or v < 0 for v in (stdout_offset, stderr_offset)) or not 1 <= limit <= 65536:
            raise self.invalid_request
        with self.lock:
            job = self.jobs.get(job_id)
            if job is None:
                raise JobError(404, 'job_not_found')
            result = {k: v for k, v in job.items() if k not in ('owner', 'fingerprint', 'stdout_b64', 'stderr_b64')}
            for stream, offset in (('stdout', stdout_offset), ('stderr', stderr_offset)):
                raw = base64.b64decode(job[stream + '_b64']) if stream + '_b64' in job else job[stream].encode()
                next_offset = min(offset + limit, len(raw))
                page = raw[offset:next_offset]
                try:
                    page.decode('utf-8')
                except UnicodeDecodeError as error:
                    if error.reason == 'unexpected end of data' and next_offset < len(raw):
                        # Do not divide one UTF-8 character across pages. A tiny
                        # limit may return one complete character (up to 4 bytes).
                        if error.start > 0:
                            next_offset = offset + error.start
                        else:
                            next_offset = min(offset + limit, len(raw))
                            while next_offset < len(raw) and raw[next_offset] & 0xc0 == 0x80:
                                next_offset += 1
                        page = raw[offset:next_offset]
                result[stream] = page.decode(errors='replace')
                result[stream + '_next_offset'] = next_offset
                result['next_' + stream + '_offset'] = next_offset
            result.update(timed_out=job['state'] == 'timed_out',
                          cancel_confirmed=job['state'] == 'cancelled',
                          truncated=job['stdout_truncated'] or job['stderr_truncated'])
            return result

    def cancel(self, job_id):
        with self.lock:
            if job_id not in self.jobs:
                raise JobError(404, 'job_not_found')
            if self.jobs[job_id]['state'] not in TERMINAL:
                self.cancel_requests.add(job_id)
            return self.snapshot(job_id)

    def has_active_jobs(self):
        with self.lock:
            return any(j['state'] not in TERMINAL for j in self.jobs.values())

    def _drain(self, job_id, stream, pipe, limit):
        raw = bytearray()
        try:
            while True:
                chunk = os.read(pipe.fileno(), 4096)
                if not chunk:
                    break
                with self.lock:
                    job = self.jobs[job_id]
                    available = limit - len(raw)
                    raw.extend(chunk[:max(0, available)])
                    job[stream] = bytes(raw).decode(errors='replace')
                    job[stream + '_b64'] = base64.b64encode(raw).decode('ascii')
                    job[stream + '_truncated'] |= len(chunk) > available
                    self._persist(job)
        finally:
            pipe.close()

    def _run(self, job_id, spec):
        start = time.monotonic()
        acquired = False
        proc = None
        try:
            while not acquired:
                with self.lock:
                    if job_id in self.cancel_requests:
                        self.jobs[job_id]['state'] = 'cancelled'
                        self._persist(self.jobs[job_id])
                        return
                acquired = self.exec_lock.acquire(timeout=.1)
            with self.lock:
                if job_id in self.cancel_requests:
                    self.jobs[job_id]['state'] = 'cancelled'
                    self._persist(self.jobs[job_id])
                    return
                proc = subprocess.Popen(['bash', '-c', spec['command']], cwd=spec['cwd'], env=spec['env'],
                                        start_new_session=True, stdin=subprocess.DEVNULL,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                self.processes[job_id] = proc
                self.jobs[job_id]['state'] = 'running'
                self._persist(self.jobs[job_id])
            start = time.monotonic()
            drains = [threading.Thread(target=self._drain, args=(job_id, name, pipe, spec['max_bytes']), daemon=True)
                      for name, pipe in (('stdout', proc.stdout), ('stderr', proc.stderr))]
            for thread in drains:
                thread.start()
            reason = None
            while proc.poll() is None:
                with self.lock:
                    cancelled = job_id in self.cancel_requests
                if cancelled or (time.monotonic() - start) * 1000 >= spec['timeout_ms']:
                    reason = 'cancelled' if cancelled else 'timed_out'
                    self.kill_group(proc, graceful=True)
                    break
                time.sleep(.05)
            proc.wait()
            # Descendants can retain pipe handles after the leader exits. End the group
            # before joining drains so a shell background command cannot hang jobs.
            try:
                os.killpg(proc.pid, 9)
            except ProcessLookupError:
                pass
            for thread in drains:
                thread.join()
            with self.lock:
                self.jobs[job_id].update(state=reason or ('succeeded' if proc.returncode == 0 else 'failed'),
                                        exit_code=proc.returncode, duration_ms=int((time.monotonic()-start)*1000))
                self._persist(self.jobs[job_id])
        except OSError:
            with self.lock:
                self.jobs[job_id].update(state='failed', exit_code=None, error='exec_failed')
                self._persist(self.jobs[job_id])
        finally:
            with self.lock:
                self.processes.pop(job_id, None)
                self.cancel_requests.discard(job_id)
            if acquired:
                self.exec_lock.release()
