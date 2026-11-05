"""Ensure APK/one-file daemon bundling includes the cold archive classes."""
import importlib.util
from pathlib import Path


def test_generated_standalone_has_cold_archive_and_no_external_module_import():
    root = Path(__file__).resolve().parents[3]
    script = root / 'scripts' / 'bundle-termux-bridge.py'
    spec = importlib.util.spec_from_file_location('closepaw_bundle_test', script)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    bundled = module.bundle(root)
    assert 'class ColdArchive:' in bundled
    assert 'from closepaw_cold_archive import ColdArchive' not in bundled
    assert 'class JobManager:' in bundled
    compile(bundled, '<bundled-bridge>', 'exec')
