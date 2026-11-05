"""Lossless, bounded-space SQLite cold index for CONFIRMED terminal daemon records.

Existing jobs/archive/*.json remain untouched. New overflow records move
from hot JSON to an indexed SQLite BLOB with byte-for-byte readback before
unlink. A crash after commit but before unlink is reconciled by matching bytes,
never by re-executing the job. This is Termux-private daemon data, not a
root/trust-boundary updater; it cannot be called as privileged authorization.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat

MAX_COLD_PAGES = 131072  # <=512 MiB @ 4096-byte pages; fail closed at bound
MAX_COLD_RECORD_BYTES = 4 * 1024 * 1024
ID_RE = re.compile(r'[a-f0-9]{32}')
SHA_RE = re.compile(r'[a-f0-9]{64}')
TERMINAL = {'succeeded', 'failed', 'timed_out', 'cancelled', 'interrupted'}
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

    def _raw_by_id(self, job_id):
        result = self.db.execute('SELECT payload FROM records WHERE job_id=?', (job_id,)).fetchone()
        return None if result is None else result[0]

    def by_id(self, job_id):
        raw = self._raw_by_id(job_id)
        if raw is None:
            return None
        record = _validate(raw)
        if record['owner'] != self.owner:
            return None
        return record

    def by_request(self, request_id):
        item = self.db.execute('SELECT payload FROM records WHERE owner=? AND request_id=?',
                               (self.owner, request_id)).fetchone()
        return None if item is None else _validate(item[0])

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
