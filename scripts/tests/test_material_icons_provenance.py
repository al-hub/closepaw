"""Guard vendor geometry/provenance and dependency/import regressions offline."""
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('material_icons_provenance', ROOT / 'scripts/verify-vendored-material-icons.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class MaterialIconsProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        manifest = json.loads(module.MANIFEST.read_text())
        # Only the files used by the checker; no network/SDK or full repo copy.
        paths = {'app/build.gradle.kts', 'LICENSE', 'doc/dev/evidence/issue135-material-icons-provenance.json',
                 'app/src/main/assets/vendor_material_icons_LICENSE.txt',
                 'app/src/main/assets/vendor_material_icons_NOTICE.txt'}
        paths.update(manifest['changed_import_files'])
        paths.update(item['vendored_path'] for item in manifest['icons'] if 'vendored_path' in item)
        # Include unchanged callers which still import core icons.
        for path in (ROOT / 'app/src/main').rglob('*.kt'):
            if module.ICON_IMPORT.search(path.read_text()): paths.add(str(path.relative_to(ROOT)))
        for relative in paths:
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, destination)
        self.manifest_path = self.root / module.MANIFEST.relative_to(ROOT)
        self.manifest = manifest

    def test_original_source_identity_and_coverage(self):
        result = module.verify(root=self.root)
        self.assertEqual(53, result['icons'])
        self.assertEqual(24, result['core'])
        self.assertEqual(29, result['vendored'])

    def test_geometry_edit_rejected_even_with_updated_vendor_hash(self):
        icon = next(x for x in self.manifest['icons'] if x['source_archive'] == 'extended')
        path = self.root / icon['vendored_path']
        data = path.read_bytes().replace(b'24.0f', b'23.0f', 1)
        if data == path.read_bytes():
            # Generated code must remain byte-identical beyond its package.
            data = data.replace(b'materialPath {', b'materialPath(fillAlpha = 0.5f) {', 1)
        self.assertNotEqual(data, path.read_bytes())
        path.write_bytes(data)
        icon['vendored_sha256'] = module.digest(data)
        self.manifest_path.write_text(json.dumps(self.manifest))
        with self.assertRaisesRegex(ValueError, 'Generated source changed'):
            module.verify(root=self.root)

    def test_original_extended_import_and_resolved_extended_rejected(self):
        icon = next(x for x in self.manifest['icons'] if x['source_archive'] == 'extended')
        caller = self.root / 'app/src/main/kotlin/Unconverted.kt'
        caller.write_text('import ' + icon['original_import'] + '\n')
        with self.assertRaisesRegex(ValueError, 'Icon import coverage changed'):
            module.verify(root=self.root)
        caller.unlink()
        report = self.root / 'resolved.json'
        report.write_text(json.dumps([
            {'dependency': 'androidx.compose.material:material-icons-core-android:1.7.6'},
            {'dependency': 'androidx.compose.material:material-icons-extended-android:1.7.6'},
        ]))
        with self.assertRaisesRegex(ValueError, 'Resolved graph still includes extended'):
            module.verify(root=self.root, resolved_license_report=report)
