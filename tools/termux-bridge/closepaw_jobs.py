"""Persistent, bounded asynchronous commands independent of HTTP connections."""
import base64
import hashlib
import json
import os
import re
import sqlite3
import stat
import subprocess
import threading
import time
import uuid
from pathlib import Path

MAX_COLD_PAGES = 131072  # <=512 MiB @ 4096-byte pages; fail closed at bound
# Two 1MiB raw output streams can each expand to 6MiB of JSON-escaped\n# replacement characters plus 4/3MiB of base64. Never reject a valid\n# confirmed terminal result merely because the index has a 4MiB row cap.\n# Bound retained row bytes without widening execution/output limits.\nMAX_COLD_RECORD_BYTES = 16 * 1024 * 1024
ID_RE = re.compile(r'[a-f0-9]{32}')
SHA_RE = re.compile(r'[a-f0-9]{64}')
REQUIRED = {'job_id','request_id','owner','fingerprint','state','stdout','stderr',
            'stdout_truncated','stderr_truncated','exit_code','outcome_unknown','duration_ms'}


class ColdArchiveError(Exception):
    pass


def _validate(raw):
    if not isinstance(raw, bytes) or not 1 <= len(raw) <= MAX_COLD_RECORD_BYTES:
        raise ColdArchiveError('invalid cold record bytes')
    try:
        record = json.loads(raw.decode('utf-8'))
    except (ValueError, UnicodeError):
        raise ColdArchiveError('invalid cold JSON') from None
    if (not isinstance(record, dict) or not REQUIRED.issubset(record) or
            not isinstance(record['job_id'], str) or not ID_RE.fullmatch(record['job_id']) or
            not isinstance(record['owner'], str) or not SHA_RE.fullmatch(record['owner']) or
            not isinstance(record['request_id'], str) or not record['request_id'] or
            not isinstance(record['fingerprint'], str) or not SHA_RE.fullmatch(record['fingerprint']) or
            not isinstance(record['state'], str) or record['state'] not in TERMINAL or
            record['outcome_unknown'] is not False or
            any(not isinstance(record[k], str) for k in ('stdout','stderr')) or
            any(type(record[k]) is not bool for k in ('stdout_truncated','stderr_truncated')) or
            (record['exit_code'] is not None and type(record['exit_code']) is not int) or
            type(record['duration_ms']) is not int or record['duration_ms'] < 0):
        raise ColdArchiveError('invalid or unconfirmed cold record')
    return record


class ColdArchive:
    def __init__(self, directory, owner, existing):
        self.path = Path(directory) / 'cold-records.sqlite3'
        self.owner = owner
        if self.path.is_symlink() or (self.path.exists() and not self.path.is_file()):
            raise ColdArchiveError('invalid cold archive path')
        if not self.path.exists():
            descriptor = os.open(self.path, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            os.close(descriptor)
            dfd = os.open(self.path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(dfd)
            finally:
                os.close(dfd)
        if stat.S_IMODE(self.path.stat().st_mode) & 0o077:
            raise ColdArchiveError('cold archive too permissive')
        try:
            self.db = sqlite3.connect(str(self.path), timeout=5, check_same_thread=False)
            self.db.execute('PRAGMA journal_mode=DELETE')
            self.db.execute('PRAGMA synchronous=FULL')
            self.db.execute('PRAGMA page_size=4096')
            self.db.execute('PRAGMA max_page_count='+str(MAX_COLD_PAGES))
            # Existing SQLite files cannot silently override the physical
            # 4096-byte page / 512MiB bound with a larger page size.
            if (self.db.execute('PRAGMA page_size').fetchone()[0] != 4096 or
                self.db.execute('PRAGMA max_page_count').fetchone()[0] > MAX_COLD_PAGES or
                self.db.execute('PRAGMA page_count').fetchone()[0] > MAX_COLD_PAGES):
                raise ColdArchiveError('cold archive size policy mismatch')
            self.db.execute(
                'CREATE TABLE IF NOT EXISTS records ('
                'job_id TEXT PRIMARY KEY, owner TEXT NOT NULL, request_id TEXT NOT NULL,'
                'fingerprint TEXT NOT NULL, sha256 TEXT NOT NULL, payload BLOB NOT NULL,'
                'UNIQUE(owner, request_id))'
            )
            result = self.db.execute('PRAGMA quick_check').fetchone()
            if result != ('ok',):
                raise ColdArchiveError('cold SQLite integrity failure')
            # Streaming check: never materialize all old records in memory.
            for job_id, row_owner, request_id, fingerprint, digest, raw in self.db.execute(
                'SELECT job_id,owner,request_id,fingerprint,sha256,payload FROM records'
            ):
                rec = _validate(raw)
                if (rec['job_id'] != job_id or rec['owner'] != row_owner or
                        rec['request_id'] != request_id or rec['fingerprint'] != fingerprint or
                        hashlib.sha256(raw).hexdigest() != digest):
                    raise ColdArchiveError('cold archive record mismatch')
            # After a power loss, JSON and SQLite may both contain the same
            # record. Accept only byte-identical copies, never discard either.
            for job_id, value in existing.items():
                raw = self._raw_by_id(job_id)
                path = Path(directory) / ('archive/' if value.get('_legacy_archive') else '') / (job_id + '.json')
                if raw is not None and (not path.is_file() or path.read_bytes() != raw):
                    raise ColdArchiveError('conflicting hot/cold history')
                # A different task ID with the same request ID would defeat
                # dedup after a daemon restart: refuse before any admission.
                if value.get('owner') == self.owner:
                    duplicate = self.by_request(value['request_id'])
                    if duplicate is not None and duplicate['job_id'] != job_id:
                        raise ColdArchiveError('conflicting request history')
        except (sqlite3.Error, OSError, ValueError, TypeError) as error:
            raise ColdArchiveError('cold archive unavailable or corrupt') from error

    @staticmethod
    def _checked_row(row):
        if row is None:
            return None
        job_id, owner, request_id, fingerprint, digest, raw = row
        record = _validate(raw)
        if (record['job_id'] != job_id or record['owner'] != owner or
            record['request_id'] != request_id or
            record['fingerprint'] != fingerprint or
            hashlib.sha256(raw).hexdigest() != digest):
            raise ColdArchiveError('cold record changed after verification')
        return record, raw

    def _raw_by_id(self, job_id):
        row = self.db.execute(
            'SELECT job_id,owner,request_id,fingerprint,sha256,payload '
            'FROM records WHERE job_id=?', (job_id,)).fetchone()
        verified = self._checked_row(row)
        return None if verified is None else verified[1]

    def by_id(self, job_id):
        row = self.db.execute(
            'SELECT job_id,owner,request_id,fingerprint,sha256,payload '
            'FROM records WHERE job_id=?', (job_id,)).fetchone()
        verified = self._checked_row(row)
        if verified is None or verified[0]['owner'] != self.owner:
            return None
        return verified[0]

    def by_request(self, request_id):
        row = self.db.execute(
            'SELECT job_id,owner,request_id,fingerprint,sha256,payload '
            'FROM records WHERE owner=? AND request_id=?',
            (self.owner, request_id)).fetchone()
        verified = self._checked_row(row)
        return None if verified is None else verified[0]

    def put(self, raw):
        record = _validate(raw)
        if record['owner'] != self.owner:
            raise ColdArchiveError('cold owner mismatch')
        old = self._raw_by_id(record['job_id'])
        if old is not None:
            if old != raw:
                raise ColdArchiveError('cold job ID conflict')
            return
        digest = hashlib.sha256(raw).hexdigest()
        try:
            self.db.execute(
                'INSERT INTO records(job_id,owner,request_id,fingerprint,sha256,payload) '
                'VALUES(?,?,?,?,?,?)',
                (record['job_id'], record['owner'], record['request_id'],
                 record['fingerprint'], digest, raw)
            )
            self.db.commit()  # synchronous=FULL
            if self._raw_by_id(record['job_id']) != raw:
                raise ColdArchiveError('cold readback mismatch')
        except sqlite3.Error as error:
            self.db.rollback()
            raise ColdArchiveError('cold archive capacity or write failure') from error


TERMINAL = {'succeeded', 'failed', 'timed_out', 'cancelled', 'interrupted'}
MAX_JOBS = 1000
MAX_ARCHIVED_JOBS = 1000
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
        self.archive = self.directory / 'archive'
        self.archive.mkdir(mode=0o700, exist_ok=True)
        self.archived = set()
        self.owner = hashlib.sha256(token.encode()).hexdigest()
        self.exec_lock, self.parse_exec = exec_lock, parse_exec
        self.invalid_request, self.kill_group = invalid_request, kill_group
        self.lock = threading.RLock()
        self.jobs, self.processes, self.cancel_requests = {}, {}, set()
        self.store_healthy = True
        for path in sorted(self.directory.glob('*.json')) + sorted(self.archive.glob('*.json')):
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
                        not isinstance(job['request_id'], str) or not isinstance(job['fingerprint'], str) or
                        not re.fullmatch(r'[a-f0-9]{64}', job['fingerprint']) or
                        any(not isinstance(job[stream], str) for stream in ('stdout', 'stderr')) or
                        any(type(job[key]) is not bool for key in
                            ('stdout_truncated', 'stderr_truncated', 'outcome_unknown')) or
                        type(job['duration_ms']) is not int or job['duration_ms'] < 0 or
                        (job['exit_code'] is not None and type(job['exit_code']) is not int)):
                    raise ValueError('invalid record')
                for stream in ('stdout', 'stderr'):
                    if stream + '_b64' in job:
                        if not isinstance(job[stream + '_b64'], str):
                            raise ValueError('invalid output')
                        base64.b64decode(job[stream + '_b64'], validate=True)
                if job['job_id'] in self.jobs:
                    raise ValueError('duplicate history')
                if path.parent == self.archive:
                    if job['state'] not in TERMINAL or job['outcome_unknown']:
                        raise ValueError('unconfirmed archived job')
                    self.archived.add(job['job_id'])
                if job['state'] not in TERMINAL:
                    job.update(state='interrupted', outcome_unknown=True, exit_code=None)
                    self._persist(job)
                self.jobs[job['job_id']] = job
            except (OSError, ValueError, KeyError, TypeError):
                # Lost history can hide a prior side effect; never accept new
                # commands until the persisted store has been repaired.
                self.store_healthy = False
        try:
            # Legacy archive JSON and active/unknown task records are NEVER
            # cleaned or migrated on opening this cold index.
            existing = {k: dict(v, _legacy_archive=k in self.archived)
                        for k, v in self.jobs.items()}
            self.cold = ColdArchive(self.directory, self.owner, existing)
        except (ColdArchiveError, OSError):
            self.cold = None
            self.store_healthy = False

    def _cold_lookup(self, key, *, by_request=False):
        if self.cold is None:
            return None
        try:
            if by_request:
                return self.cold.by_request(key)
            return self.cold.by_id(key)
        except (ColdArchiveError, sqlite3.Error):
            # Never allow tampered cold identity/hash data to authorize a
            # duplicate command or present an unverified result.
            self.store_healthy = False
            raise JobError(503, 'job_store_unhealthy')

    def _persist(self, job):
        parent = self.archive if job['job_id'] in self.archived else self.directory
        destination = parent / (job['job_id'] + '.json')
        temporary = destination.with_suffix('.tmp')
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, 'w') as handle:
            json.dump(job, handle, separators=(',', ':'))
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, destination)

    def _spill_to_cold(self, job_id, job):
        """Commit exact bytes before retiring the redundant hot JSON copy."""
        path = self.directory / (job_id + '.json')
        try:
            raw = path.read_bytes()
            if json.loads(raw) != job or self.cold is None:
                raise ColdArchiveError('hot record changed or index unavailable')
            self.cold.put(raw)
            if self.cold._raw_by_id(job_id) != raw:
                raise ColdArchiveError('cold record verification failed')
            path.unlink()
            dfd = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(dfd)
            finally:
                os.close(dfd)
            self.jobs.pop(job_id)
        except (ColdArchiveError, OSError, ValueError, sqlite3.Error):
            # The hot JSON survives until cold readback; a crash after the
            # SQLite commit is detected as an equal duplicate on restart.
            self.store_healthy = False
            raise JobError(503, 'job_cold_archive_unavailable')

    def _reclaim_capacity(self):
        """Retain confirmed history beyond the old 1000+1000 limit, no eviction."""
        if len(list(self.directory.glob('*.json'))) < MAX_JOBS:
            return
        legacy_full = len(list(self.archive.glob('*.json'))) >= MAX_ARCHIVED_JOBS
        for job_id, job in list(self.jobs.items()):
            if (job_id not in self.archived and job_id not in self.processes and
                    job['state'] in TERMINAL and not job['outcome_unknown']):
                if legacy_full:
                    self._spill_to_cold(job_id, job)
                    return
                source = self.directory / (job_id + '.json')
                destination = self.archive / source.name
                if destination.exists():
                    raise JobError(503, 'job_archive_conflict')
                try:
                    os.replace(source, destination)
                    self.archived.add(job_id)
                    for parent in (self.archive, self.directory):
                        descriptor = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
                        try:
                            os.fsync(descriptor)
                        finally:
                            os.close(descriptor)
                except OSError:
                    self.store_healthy = False
                    raise JobError(503, 'job_archive_unavailable')
                self.archived.add(job_id)
                return
        raise JobError(429, 'job_store_full_no_confirmed_results')

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
            archived = self._cold_lookup(request_id, by_request=True)
            if archived:
                if archived['fingerprint'] != fingerprint:
                    raise JobError(409, 'request_id_conflict')
                return self.snapshot(archived['job_id'])
            self._reclaim_capacity()
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
                job = self._cold_lookup(job_id)
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
                    if (error.reason == 'unexpected end of data' and next_offset == len(raw) and
                            job['state'] in ('queued', 'running') and not job[stream + '_truncated']):
                        next_offset = offset + error.start
                        page = raw[offset:next_offset]
                    elif error.reason == 'unexpected end of data' and next_offset < len(raw):
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
            job = self.jobs.get(job_id)
            if job is None:
                # Preserve legacy terminal-result semantics after spill:
                # a cancel query must not replay work or report a stored
                # terminal job as missing.
                archived = self._cold_lookup(job_id)
                if archived is None:
                    raise JobError(404, 'job_not_found')
                return self.snapshot(job_id)
            if job['state'] not in TERMINAL:
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
