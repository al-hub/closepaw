import copy
import importlib.util
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('native_cache', Path(__file__).parents[1] / 'native-runtime-cache.py')
cache = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(cache)


def executable(machine=183):
    data = bytearray(124)
    data[:7] = b'\x7fELF\x02\x01\x01'
    struct.pack_into('<HHI', data, 16, 3, machine, 1)
    struct.pack_into('<Q', data, 32, 64)
    struct.pack_into('<HHH', data, 52, 64, 56, 1)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, 0x400000, 0x400000, 124, 124, 4096)
    return bytes(data)


class NativeCacheTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        for name in ['scripts/build-cloudflared-android.sh', 'scripts/spike-build-openai-tunnel-android.sh',
                     'scripts/native-runtime-cache.py', '.github/actions/native-runtime-cache/action.yml']:
            p = self.root / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(name)
        self.inputs = cache.spec(self.root, 'cloudflared')
        self.directory = self.root / 'cached/cloudflared'
        self.directory.mkdir(parents=True)
        self.binary = self.directory / 'libcloudflared.so'
        self.binary.write_bytes(executable())
        self.metadata = dict(schema=cache.SCHEMA, inputs=self.inputs,
                             inputs_digest=cache.digest(self.inputs), key=cache.key(self.inputs),
                             size=self.binary.stat().st_size, sha256=cache.sha256(self.binary.read_bytes()))
        self.write_metadata()

    def tearDown(self):
        self.tmp.cleanup()

    def write_metadata(self):
        (self.directory / 'manifest.json').write_text(json.dumps(self.metadata))

    def test_exact_restore_and_stage(self):
        binary = cache.verify(self.directory, self.inputs)
        cache.stage(binary, self.root, 'cloudflared')
        output = self.root / 'app/src/main/jniLibs/arm64-v8a/libcloudflared.so'
        self.assertEqual(executable(), output.read_bytes())
        self.assertEqual(0o755, output.stat().st_mode & 0o777)

    def test_wrong_input_key_and_poisoned_manifest_rejected(self):
        for field in ['source_commit', 'go', 'ndk', 'api', 'abi', 'cgo', 'flags', 'environment', 'scripts']:
            poisoned = copy.deepcopy(self.metadata)
            poisoned['inputs'][field] = 'changed'
            (self.directory / 'manifest.json').write_text(json.dumps(poisoned))
            with self.subTest(field=field), self.assertRaises(ValueError):
                cache.verify(self.directory, self.inputs)
        for field in ['key', 'inputs_digest', 'sha256', 'size', 'schema']:
            self.metadata[field] = 'wrong'
            self.write_metadata()
            with self.subTest(field=field), self.assertRaises(ValueError):
                cache.verify(self.directory, self.inputs)
            self.setUp_manifest()

    def setUp_manifest(self):
        self.metadata = dict(schema=cache.SCHEMA, inputs=self.inputs,
                             inputs_digest=cache.digest(self.inputs), key=cache.key(self.inputs),
                             size=self.binary.stat().st_size, sha256=cache.sha256(self.binary.read_bytes()))
        self.write_metadata()

    def test_modified_binary_and_wrong_elf_arch_rejected_even_with_matching_hash(self):
        self.binary.write_bytes(executable() + b'poison')
        with self.assertRaises(ValueError): cache.verify(self.directory, self.inputs)
        self.binary.write_bytes(executable(machine=62))
        self.setUp_manifest()
        with self.assertRaises(ValueError): cache.verify(self.directory, self.inputs)

    def test_bad_elf_layout_and_non_executable_rejected(self):
        for data in [b'not ELF', executable()[:80], executable()[:64]]:
            with self.subTest(size=len(data)), self.assertRaises(ValueError): cache.check_elf(data)
        data = bytearray(executable()); struct.pack_into('<I', data, 68, 4)
        with self.assertRaises(ValueError): cache.check_elf(bytes(data))

    def test_symlink_and_missing_file_rejected(self):
        self.binary.unlink(); self.binary.symlink_to(self.root / 'elsewhere')
        with self.assertRaises(ValueError): cache.verify(self.directory, self.inputs)
        self.binary.unlink()
        with self.assertRaises(OSError): cache.verify(self.directory, self.inputs)

    def test_build_scripts_action_and_runtime_inputs_invalidate_key(self):
        initial = cache.key(self.inputs)
        for name in self.inputs['scripts']:
            path = self.root / name; before = path.read_text(); path.write_text(before + 'changed')
            self.assertNotEqual(initial, cache.key(cache.spec(self.root, 'cloudflared')))
            path.write_text(before)
        for field in ['source_commit', 'go', 'ndk', 'api', 'abi', 'cgo', 'flags']:
            inputs = copy.deepcopy(self.inputs); inputs[field] = 'changed'
            self.assertNotEqual(initial, cache.key(inputs))
        self.assertNotEqual(initial, cache.key(cache.spec(self.root, 'tunnel')))

    def test_app_sha_and_app_source_are_not_native_cache_inputs(self):
        with patch.dict(os.environ, GITHUB_SHA='a' * 40): first = cache.key(cache.spec(self.root, 'cloudflared'))
        with patch.dict(os.environ, GITHUB_SHA='b' * 40): second = cache.key(cache.spec(self.root, 'cloudflared'))
        self.assertEqual(first, second)

    def test_pack_checks_actual_compiler_versions(self):
        source = self.root / 'app/src/main/jniLibs/arm64-v8a/libcloudflared.so'
        source.parent.mkdir(parents=True); source.write_bytes(executable())
        sdk = self.root / 'sdk'; ndk = sdk / 'ndk' / cache.NDK_VERSION
        ndk.mkdir(parents=True); (ndk / 'source.properties').write_text('Pkg.Revision = ' + cache.NDK_VERSION)
        with patch.dict(os.environ, ANDROID_HOME=str(sdk)), patch.object(cache.subprocess, 'check_output', return_value='go1.27.0\n'):
            cache.pack(self.directory, self.inputs, self.root)
        self.assertEqual(self.binary, cache.verify(self.directory, self.inputs))
        (ndk / 'source.properties').write_text('Pkg.Revision = wrong')
        with patch.dict(os.environ, ANDROID_HOME=str(sdk)), patch.object(cache.subprocess, 'check_output', return_value='go1.27.0\n'), self.assertRaises(ValueError):
            cache.pack(self.directory, self.inputs, self.root)
        with patch.object(cache.subprocess, 'check_output', return_value='go1.26.9\n'), self.assertRaises(ValueError):
            cache.pack(self.directory, self.inputs, self.root)

    def test_main_only_write_policy_excludes_pr_target_and_other_events(self):
        for event in ('push', 'workflow_dispatch', 'schedule'):
            self.assertTrue(cache.cache_write_allowed('refs/heads/main', event))
        for event in ('pull_request', 'pull_request_target', 'workflow_run', None):
            self.assertFalse(cache.cache_write_allowed('refs/heads/main', event))
        for ref in ('refs/heads/feature', 'refs/pull/123/merge', 'refs/tags/v1', None):
            self.assertFalse(cache.cache_write_allowed(ref, 'push'))

    def test_restore_cli_nonobject_manifest_falls_back(self):
        for metadata in ([], None, 42, 'string'):
            with self.subTest(metadata=metadata):
                self.directory.mkdir(parents=True, exist_ok=True)
                self.binary.write_bytes(executable())
                (self.directory / 'manifest.json').write_text(json.dumps(metadata))
                argv = ['native-runtime-cache.py', 'restore', '--runtime', 'cloudflared', '--root', str(self.root), '--cache-root', str(self.directory.parent)]
                with patch.object(cache.sys, 'argv', argv): cache.main()
                self.assertFalse(self.directory.exists())
                self.assertFalse((self.root / 'app/src/main/jniLibs/arm64-v8a/libcloudflared.so').exists())

    def test_restore_cli_miss_falls_back_without_staging_poison(self):
        self.binary.write_bytes(b'poison')
        argv = ['native-runtime-cache.py', 'restore', '--runtime', 'cloudflared', '--root', str(self.root), '--cache-root', str(self.directory.parent)]
        with patch.object(cache.sys, 'argv', argv): cache.main()
        self.assertFalse(self.directory.exists())
        self.assertFalse((self.root / 'app/src/main/jniLibs/arm64-v8a/libcloudflared.so').exists())


if __name__ == '__main__':
    unittest.main()
