#!/usr/bin/env python3
"""Verify package-only Material icon copies and their original Maven provenance.

Default: offline package/body/hash/import coverage check, without downloading.
--upstream-dir: additionally checks original pinned source JARs + BOM from Maven.
--resolved-license-report: after a Gradle build, verify the resolved dependency
report contains the expected core version and no extended icon artifact.
This proves unchanged generated source, not an emulator or device render result.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'doc/dev/evidence/issue135-material-icons-provenance.json'
SOURCE_ROOT = ROOT / 'app/src/main/kotlin/ai/closepaw/ui/icons/vendor'
ICON_IMPORT = re.compile(
    r'^import ((?:androidx\.compose\.material\.icons|ai\.closepaw\.ui\.icons\.vendor)'
    r'\.(?:automirrored\.)?(?:filled|outlined|rounded|twotone|sharp)\.[\w]+)', re.M)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify(root=ROOT, upstream_dir=None, resolved_license_report=None):
    manifest = json.loads((root / MANIFEST.relative_to(ROOT)).read_text())
    build = (root / 'app/build.gradle.kts').read_text()
    require('compose-bom:' + manifest['compose_bom'] in build, 'BOM changed: reverify icon provenance')
    require('implementation("androidx.compose.material:material-icons-core")' in build, 'core dependency missing')
    require(not re.search(r'implementation\([^\n]*material-icons-extended', build), 'extended dependency reintroduced')
    entries = manifest['icons']
    expected_imports = set()
    copied_paths = set()
    for item in entries:
        expected_imports.add(item.get('vendored_import', item['original_import']))
        if item['source_archive'] == 'extended':
            path = root / item['vendored_path']
            copied_paths.add(path)
            data = path.read_bytes()
            original_decl = ('package ' + item['original_package'] + '\n').encode()
            vendor_decl = ('package ' + item['vendor_package'] + '\n').encode()
            require(data.count(vendor_decl) == 1, f'Unexpected package: {path}')
            require(digest(data) == item['vendored_sha256'], f'Vendor bytes changed: {path}')
            # Whole-file identity, not only path coordinates: preserves annotations,
            # viewport/default sizing, autoMirror, winding/fill/stroke and cache code.
            original = data.replace(vendor_decl, original_decl, 1)
            require(digest(original) == item['upstream_sha256'], f'Generated source changed: {path}')
            require(b'Copyright 2024 The Android Open Source Project' in data, f'Copyright missing: {path}')
            require(b'Licensed under the Apache License, Version 2.0' in data, f'License header missing: {path}')
    actual_copies = set((root / SOURCE_ROOT.relative_to(ROOT)).rglob('*.kt'))
    require(actual_copies == copied_paths, 'Unexpected or missing vendored Kotlin source')
    imports = {m.group(1) for path in (root / 'app/src/main').rglob('*.kt')
               for m in ICON_IMPORT.finditer(path.read_text())}
    require(imports == expected_imports, 'Icon import coverage changed; update and verify provenance manifest')
    require(len(expected_imports) == len(entries), 'Duplicate icon identity in manifest')
    assets = root / 'app/src/main/assets'
    require((assets / 'vendor_material_icons_LICENSE.txt').read_bytes() == (root / 'LICENSE').read_bytes(), 'Packaged Apache license changed')
    notice = (assets / 'vendor_material_icons_NOTICE.txt').read_text()
    require(manifest['copyright'] in notice and 'Apache License, Version 2.0' in notice, 'Packaged attribution missing')
    require('material-icons-extended-android:' + manifest['version'] in notice, 'Packaged source version missing')

    if upstream_dir:
        upstream_dir = Path(upstream_dir)
        archives = {}
        for name, archive in manifest['archives'].items():
            path = upstream_dir / archive['filename']
            data = path.read_bytes()
            require(digest(data) == archive['sha256'] and len(data) == archive['bytes'], f'Upstream archive mismatch: {name}')
            if name != 'bom':
                archives[name] = zipfile.ZipFile(path)
        for name, artifact in manifest.get('binary_inputs', {}).items():
            path = upstream_dir / artifact['filename']
            data = path.read_bytes()
            require(digest(data) == artifact['sha256'] and len(data) == artifact['aar_bytes'], f'Upstream binary mismatch: {name}')
            with zipfile.ZipFile(path) as aar:
                classes = aar.read('classes.jar')
            require(digest(classes) == artifact['classes_jar_sha256'] and len(classes) == artifact['classes_jar_bytes'], f'Upstream classes.jar mismatch: {name}')
            from io import BytesIO
            with zipfile.ZipFile(BytesIO(classes)) as jar:
                class_entries = [entry for entry in jar.infolist() if entry.filename.endswith('.class')]
            require(len(class_entries) == artifact['class_files'] and sum(entry.file_size for entry in class_entries) == artifact['uncompressed_class_bytes'], f'Upstream class count/size mismatch: {name}')
        for item in entries:
            original = archives[item['source_archive']].read(item['source_entry'])
            require(digest(original) == item['upstream_sha256'], f'Upstream icon mismatch: {item["original_import"]}')
        bom = ET.fromstring((upstream_dir / manifest['archives']['bom']['filename']).read_bytes())
        ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
        versions = {x.findtext('m:artifactId', namespaces=ns): x.findtext('m:version', namespaces=ns)
                    for x in bom.findall('.//m:dependency', ns)}
        require(all(versions.get('material-icons-' + x) == manifest['version']
                    for x in ('core-android', 'extended-android')), 'BOM icons version mismatch')
    if resolved_license_report:
        report = json.loads(Path(resolved_license_report).read_text())
        coordinates = [x.get('dependency', '') for x in report]
        require('androidx.compose.material:material-icons-core-android:' + manifest['version'] in coordinates,
                'Resolved core version does not match source provenance')
        require(not any('androidx.compose.material:material-icons-extended' in x for x in coordinates),
                'Resolved graph still includes extended icons')
    return {'icons': len(entries), 'core': sum(x['source_archive'] == 'core' for x in entries),
            'vendored': len(copied_paths), 'upstream_archives_verified': bool(upstream_dir),
            'resolved_graph_verified': bool(resolved_license_report)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream-dir', type=Path)
    parser.add_argument('--resolved-license-report', type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(upstream_dir=args.upstream_dir, resolved_license_report=args.resolved_license_report)))
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        print(f'Material icon verification failed: {error}', file=sys.stderr)
        sys.exit(1)
