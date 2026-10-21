#!/usr/bin/env python3
"""Exact-input, unsigned native runtime cache. Never executes restored binaries."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys

SCHEMA = 1
GO_VERSION = '1.27.0'
NDK_VERSION = '27.2.12479018'
CLOUD_COMMIT = '18cdfe0a6fc7b72a0702d255a1f984e776ce0498'
TUNNEL_COMMIT = 'a390c168ff1b2d14e73a95991c186c6aba3ff5a0'
TOOL_ENVIRONMENT = dict(GOTOOLCHAIN='local', GOFLAGS='', GOEXPERIMENT='', GOARM64='v8.0',
                        GOENV='off', GOWORK='off', CGO_CFLAGS='-O2 -g',
                        CGO_CXXFLAGS='-O2 -g', CGO_CPPFLAGS='', CGO_LDFLAGS='-O2 -g')
RUNTIMES = {
    'cloudflared': {'commit': CLOUD_COMMIT, 'script': 'scripts/build-cloudflared-android.sh',
                   'file': 'libcloudflared.so', 'cgo': 1,
                   'flags': ['-trimpath', '-s -w -extldflags=-Wl,-z,max-page-size=16384']},
    'tunnel': {'commit': TUNNEL_COMMIT, 'script': 'scripts/spike-build-openai-tunnel-android.sh',
               'file': 'libopenaitunnel.so', 'cgo': 0,
               'flags': ['make tunnel-client-runtime']},
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def spec(root, runtime):
    config = RUNTIMES[runtime]
    scripts = [config['script'], 'scripts/native-runtime-cache.py',
               '.github/actions/native-runtime-cache/action.yml']
    return dict(schema=SCHEMA, runtime=runtime, source_commit=config['commit'],
                go=GO_VERSION, ndk=NDK_VERSION if config['cgo'] else None,
                host='Linux-X64', goos='android', abi='arm64-v8a', goarch='arm64',
                api=31, cgo=config['cgo'], flags=config['flags'], environment=TOOL_ENVIRONMENT,
                scripts={name: sha256((root / name).read_bytes()) for name in scripts})


def digest(inputs):
    return sha256(json.dumps(inputs, sort_keys=True, separators=(',', ':')).encode())


def key(inputs):
    return f"closepaw-native-v{SCHEMA}-{inputs['runtime']}-{digest(inputs)}"


def check_elf(data):
    if len(data) < 64 or data[:7] != b'\x7fELF\x02\x01\x01':
        raise ValueError('not an ELF64 little-endian executable')
    etype, machine, version = struct.unpack_from('<HHI', data, 16)
    if etype not in (2, 3) or machine != 183 or version != 1:
        raise ValueError('ELF is not an AArch64 executable')
    phoff = struct.unpack_from('<Q', data, 32)[0]
    ehsize, phentsize, phnum = struct.unpack_from('<HHH', data, 52)
    if ehsize != 64 or phentsize != 56 or not 0 < phnum <= 128:
        raise ValueError('invalid ELF program-header dimensions')
    if phoff < 64 or phoff + phnum * phentsize > len(data):
        raise ValueError('truncated ELF program headers')
    executable_load = False
    for index in range(phnum):
        ptype, flags, offset, _, _, filesz, memsz, _ = struct.unpack_from('<IIQQQQQQ', data, phoff + index * 56)
        if ptype == 1:
            if filesz > memsz or offset + filesz > len(data):
                raise ValueError('invalid ELF LOAD segment')
            executable_load |= bool(flags & 1 and filesz)
    if not executable_load:
        raise ValueError('ELF has no executable LOAD segment')


def verify(directory, inputs):
    if directory.is_symlink():
        raise ValueError('cache directory symlinks are not allowed')
    manifest = directory / 'manifest.json'
    binary = directory / RUNTIMES[inputs['runtime']]['file']
    if manifest.is_symlink() or binary.is_symlink():
        raise ValueError('cache symlinks are not allowed')
    if manifest.stat().st_size > 65536 or binary.stat().st_size > 512 * 1024 * 1024:
        raise ValueError('native cache entry exceeds size limits')
    metadata = json.loads(manifest.read_text())
    if not isinstance(metadata, dict):
        raise ValueError('native cache manifest must be an object')
    if metadata.get('inputs') != inputs or metadata.get('inputs_digest') != digest(inputs):
        raise ValueError('native cache input mismatch')
    if metadata.get('key') != key(inputs) or metadata.get('schema') != SCHEMA:
        raise ValueError('native cache key/schema mismatch')
    data = binary.read_bytes()
    if metadata.get('sha256') != sha256(data) or metadata.get('size') != len(data):
        raise ValueError('native cache checksum/size mismatch')
    check_elf(data)
    return binary


def cache_write_allowed(ref, event):
    return ref == 'refs/heads/main' and event in ('push', 'workflow_dispatch', 'schedule')


def emit(name, value):
    value = str(value).lower() if isinstance(value, bool) else str(value)
    print(f'{name}={value}')
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as handle:
            handle.write(f'{name}={value}\n')


def stage(binary, root, runtime):
    output = root / 'app/src/main/jniLibs/arm64-v8a' / RUNTIMES[runtime]['file']
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(binary, output)
    output.chmod(0o755)


def pack(directory, inputs, root):
    # A miss must build with the exact Go/NDK inputs claimed by the key.
    actual_go = subprocess.check_output(['go', 'env', 'GOVERSION'], text=True).strip()
    if actual_go != f'go{GO_VERSION}':
        raise ValueError(f'expected Go {GO_VERSION}, got {actual_go}')
    if inputs['cgo']:
        ndk = Path(os.environ['ANDROID_HOME']) / 'ndk' / NDK_VERSION
        properties = dict(line.split('=', 1) for line in (ndk / 'source.properties').read_text().splitlines() if '=' in line)
        revision = next((value.strip() for name, value in properties.items() if name.strip() == 'Pkg.Revision'), '')
        if revision != NDK_VERSION:
            raise ValueError('Android NDK revision mismatch')
    source = root / 'app/src/main/jniLibs/arm64-v8a' / RUNTIMES[inputs['runtime']]['file']
    data = source.read_bytes()
    check_elf(data)
    directory.mkdir(parents=True, exist_ok=True)
    binary = directory / source.name
    shutil.copyfile(source, binary)
    metadata = dict(schema=SCHEMA, inputs=inputs, inputs_digest=digest(inputs), key=key(inputs),
                    sha256=sha256(data), size=len(data),
                    producer=dict(repository=os.environ.get('GITHUB_REPOSITORY', ''),
                                  ref=os.environ.get('GITHUB_REF', ''), sha=os.environ.get('GITHUB_SHA', '')))
    (directory / 'manifest.json').write_text(json.dumps(metadata, sort_keys=True, indent=2) + '\n')
    verify(directory, inputs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['plan', 'restore', 'pack', 'validate'])
    parser.add_argument('--runtime', choices=['cloudflared', 'tunnel', 'all'], required=True)
    parser.add_argument('--root', type=Path, default=Path.cwd())
    parser.add_argument('--cache-root', type=Path, required=True)
    args = parser.parse_args()
    names = list(RUNTIMES) if args.runtime == 'all' else [args.runtime]
    if args.command == 'plan':
        emit('cache-directory', args.cache_root)
        emit('allow-write', cache_write_allowed(os.environ.get('GITHUB_REF'), os.environ.get('GITHUB_EVENT_NAME')))
    for name in names:
        inputs = spec(args.root, name)
        directory = args.cache_root / name
        if args.command == 'plan':
            emit(f'{name}-key', key(inputs))
            emit(f'{name}-path', directory)
        elif args.command == 'pack':
            pack(directory, inputs, args.root)
        elif args.command == 'validate':
            print(f'{name}: verified {verify(directory, inputs).name}')
        else:
            try:
                binary = verify(directory, inputs)
                stage(binary, args.root, name)
                valid = True
            except (OSError, ValueError, KeyError, TypeError) as error:
                print(f'Native {name} cache unavailable/invalid; build fallback: {error}', file=sys.stderr)
                # Remove only the exact runtime directory, never arbitrary cache-root contents.
                if directory.is_symlink():
                    directory.unlink()
                elif directory.exists():
                    shutil.rmtree(directory)
                valid = False
            emit(f'{name}-valid', valid)


if __name__ == '__main__':
    main()
