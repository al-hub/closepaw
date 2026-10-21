#!/usr/bin/env python3
"""Fail closed before signing an APK from an exact trusted main CI run."""
import hashlib
import json
import pathlib
import sys


def verify(run, metadata, apk, repository, sha, version, code):
    required = {
        'head_sha': sha, 'head_branch': 'main', 'conclusion': 'success',
        'status': 'completed', 'path': '.github/workflows/security-ci.yml',
    }
    for key, value in required.items():
        if run.get(key) != value:
            raise ValueError(f'untrusted CI {key}')
    if run.get('event') not in ('push', 'workflow_dispatch', 'schedule'):
        raise ValueError('untrusted CI event')
    for key in ('repository', 'head_repository'):
        if run.get(key, {}).get('full_name') != repository:
            raise ValueError('untrusted CI repository')
    expected = {'source_commit': sha, 'run_id': str(run['id']),
                'version': version, 'version_code': str(code)}
    for key, value in expected.items():
        if metadata.get(key) != value:
            raise ValueError(f'APK metadata mismatch: {key}')
    digest = hashlib.sha256(apk).hexdigest()
    if metadata.get('sha256') != digest:
        raise ValueError('unsigned APK checksum mismatch')
    return digest


if __name__ == '__main__':
    directory, run_path, repo, sha, version, code = sys.argv[1:]
    directory = pathlib.Path(directory)
    digest = verify(json.loads(pathlib.Path(run_path).read_text()),
                    json.loads((directory / 'metadata.json').read_text()),
                    (directory / 'app-release-unsigned.apk').read_bytes(),
                    repo, sha, version, code)
    print(f'Trusted main CI unsigned APK SHA-256: {digest}')
