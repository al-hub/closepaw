#!/usr/bin/env python3
"""Fail-closed acceptance planning/reporting. No device actions, replay, or release dispatch."""
import argparse
import fnmatch
import hashlib
import json
import math
import os
import platform
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHA = re.compile(r'^[0-9a-f]{40}$')
SIGNER = re.compile(r'^[0-9a-f]{64}$')


def load_config():
    return json.loads((ROOT / 'qa/acceptance/catalog.json').read_text())


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()


def changes(base, head):
    # --no-renames includes both deleted and added paths in the impact analysis.
    return git('diff', '--no-renames', '--name-only', base, head).splitlines()


def test_inventory(source_directory=None):
    """Expand current checked-out Kotlin test sources, including new regression classes.

    This deliberately supports the repository's plain JUnit4 top-level class convention.
    A file with tests that cannot be classified blocks reporting rather than disappearing.
    """
    directory = source_directory or ROOT / 'app/src/test'
    inventory = {}
    for path in sorted(directory.rglob('*.kt')):
        source = path.read_text()
        if not re.search(r'@(?:org\.junit\.)?Test\b', source):
            continue
        package = re.search(r'^package\s+([\w.]+)', source, re.M)
        classes = list(re.finditer(r'^(?:(?:public|internal|open|abstract)\s+)*class\s+(\w+)', source, re.M))
        if not package or not classes:
            raise ValueError(f'Unclassified test source: {path.relative_to(ROOT)}')
        classified = 0
        for index, match in enumerate(classes):
            end = classes[index + 1].start() if index + 1 < len(classes) else len(source)
            count = len(re.findall(r'@(?:org\.junit\.)?Test\b', source[match.end():end]))
            if count:
                inventory[package[1] + '.' + match[1]] = count
                classified += count
        if classified != len(re.findall(r'@(?:org\.junit\.)?Test\b', source)):
            raise ValueError(f'Unclassified test methods: {path.relative_to(ROOT)}')
    if not inventory:
        raise ValueError('No checked-out test source inventory')
    return inventory


def plan(paths, config, inventory=None):
    if not {'security', 'cancellation'} <= set(config['mandatory']):
        raise ValueError('Security and cancellation regression groups are always mandatory')
    groups = set(config['mandatory'])
    reasons = []
    full = False
    for path in paths:
        matches = [rule for rule in config['rules'] if any(fnmatch.fnmatchcase(path, p) for p in rule['paths'])]
        if not matches:
            full = True
            reasons.append({'path': path, 'reason': 'unmapped: all groups and full JVM required'})
        for rule in matches:
            groups.update(rule['groups'])
            full |= rule.get('full_jvm', False)
            reasons.append({'path': path, 'groups': rule['groups']})
    if full:
        groups.update(config['groups'])
    while True:
        expanded = groups | {dep for g in groups for dep in config['groups'][g].get('depends_on', [])}
        if expanded == groups: break
        groups = expanded
    inventory = test_inventory() if inventory is None else inventory
    selectors = sorted({t for g in groups for t in config['groups'][g]['jvm']})
    required = inventory if full else {c: count for c, count in inventory.items() if any(fnmatch.fnmatchcase(c, t) for t in selectors)}
    unresolved = [t for t in selectors if not any(fnmatch.fnmatchcase(c, t) for c in inventory)]
    physical = [p for p in config['physical'] if set(p['groups']) & (groups - set(config['mandatory']))]
    return dict(changed_paths=sorted(set(paths)), groups=sorted(groups), full_jvm=full,
                jvm_selectors=selectors, required_jvm_classes=required, unresolved_selectors=unresolved,
                ui_classes=sorted({t for g in groups for t in config['groups'][g].get('ui', [])}),
                physical=physical, reasons=reasons,
                policy='Selection is a fast local check; full Security CI and existing UI gate remain mandatory for release.')


def validate_record(record):
    required = {'test_id', 'issues', 'feature', 'source_commit', 'apk', 'environment', 'input', 'expected', 'observed', 'raw_logs', 'status', 'dependencies'}
    if not required <= record.keys(): raise ValueError('incomplete evidence record')
    if not SHA.fullmatch(record['source_commit']): raise ValueError('immutable source SHA required')
    if record['status'] not in {'PASS', 'FAIL', 'NOT_TESTED', 'REUSED_EVIDENCE'}: raise ValueError('invalid evidence status')
    if not isinstance(record['apk']['version_code'], int) or not SIGNER.fullmatch(record['apk']['signer_sha256']): raise ValueError('APK provenance required')
    if not record['raw_logs'] or not record['environment'] or not record['issues']: raise ValueError('raw logs, issues, environment required')
    hashes = record.get('raw_log_sha256', {})
    for reference in record['raw_logs']:
        if reference.startswith('https://'):
            if not re.fullmatch(r'https://github\.com/al-hub/closepaw/issues/\d+#issuecomment-\d+', reference):
                raise ValueError('External evidence requires a specific repository issue-comment reference')
            continue
        path = (ROOT / reference).resolve()
        if not path.is_relative_to(ROOT) or not path.is_file():
            raise ValueError('Local raw evidence must exist inside the repository')
        expected_hash = hashes.get(reference)
        if not expected_hash or not SIGNER.fullmatch(expected_hash) or hashlib.sha256(path.read_bytes()).hexdigest() != expected_hash:
            raise ValueError('Local raw evidence content hash mismatch/missing')
    return record


def reuse(record, changed_paths, target_sha, target_code):
    validate_record(record)
    overlap = [p for p in changed_paths if any(fnmatch.fnmatchcase(p, d) for d in record['dependencies'])]
    eligible = record['status'] == 'PASS' and bool(record['dependencies']) and not overlap
    return dict(record, status='REUSED_EVIDENCE' if eligible else 'NOT_TESTED', historical_status=record['status'],
                current_version_acceptance=False, target_commit=target_sha, target_version_code=target_code,
                reuse_reason='Historical evidence only; dependency diff has no overlap' if eligible else 'Affected or unbounded dependencies; historical evidence retained, fresh acceptance required', affected_paths=overlap)


def xml_results(directory):
    files = sorted(directory.rglob('TEST-*.xml')) if directory.exists() else []
    cases, classes = {}, {}
    duplicates, malformed = [], []
    for path in files:
        data = path.read_text()
        if '<!DOCTYPE' in data or '<!ENTITY' in data:
            raise ValueError('DTD/entities not permitted in test reports')
        root = ET.fromstring(data)
        if root.tag not in {'testsuite', 'testsuites'} or not list(root.iter('testsuite')):
            malformed.append(str(path) + ': missing JUnit testsuite root')
        for suite in root.iter('testsuite'):
            children = list(suite.iter('testcase'))
            observed = dict(tests=len(children), failures=sum(c.find('failure') is not None for c in children),
                            errors=sum(c.find('error') is not None for c in children), skipped=sum(c.find('skipped') is not None for c in children))
            if suite.find('error') is not None or suite.find('failure') is not None:
                malformed.append(str(path) + ': suite-level error/failure')
            for field, count in observed.items():
                if field in suite.attrib:
                    try:
                        if int(suite.attrib[field]) != count:
                            malformed.append(str(path) + ': inconsistent ' + field)
                    except ValueError:
                        malformed.append(str(path) + ': invalid ' + field)
        for case in root.iter('testcase'):
            classname, name = case.attrib.get('classname', ''), case.attrib.get('name', '')
            if not classname or not name:
                malformed.append(str(path) + ': unnamed test case')
            key = classname + '.' + name
            status = 'FAIL' if case.find('failure') is not None or case.find('error') is not None else 'NOT_TESTED' if case.find('skipped') is not None else 'PASS'
            if key in cases:
                duplicates.append(key)
            cases[key] = status
            classes[classname] = classes.get(classname, 0) + 1
    return dict(files=len(files), count=len(cases), failed=sum(s=='FAIL' for s in cases.values()),
                skipped=sum(s=='NOT_TESTED' for s in cases.values()), duplicates=duplicates, malformed=malformed,
                cases=cases, classes=classes, raw_logs=[str(p) for p in files],
                passed=bool(cases) and not duplicates and not malformed and all(s=='PASS' for s in cases.values()))


def performance(baseline, candidate):
    required = {'device', 'os', 'runtime', 'configuration', 'workload', 'execution_path', 'network', 'scope', 'clock_domain'}
    if not required <= baseline['conditions'].keys() or baseline['conditions'] != candidate['conditions']:
        raise ValueError('Matched device/environment/path/scope/clock conditions required')
    if any(not isinstance(baseline['conditions'][key], str) or not baseline['conditions'][key].strip() for key in required):
        raise ValueError('Explicit nonempty conditions required; use not_applicable only for a genuinely irrelevant dimension')
    for cohort in (baseline, candidate):
        if not SHA.fullmatch(cohort.get('source_commit', '')):
            raise ValueError('Immutable cohort source provenance required')
    if baseline['conditions']['clock_domain'] not in {'process_monotonic', 'device_monotonic', 'client_monotonic'}:
        raise ValueError('Single monotonic clock domain required; never subtract different clocks')
    def stats(cohort):
        samples = cohort['samples']
        if len(samples) < 10: raise ValueError('At least 10 raw trials per cohort required')
        for s in samples:
            if type(s['correct']) is not bool or not math.isfinite(s['duration_ms']) or s['duration_ms'] < 0:
                raise ValueError('Invalid duration/correctness sample')
            if any(type(s[k]) is not int or s[k] < 0 for k in ('calls', 'retries')): raise ValueError('Invalid call/retry count')
            for value in s.get('stage_ms', {}).values():
                if not math.isfinite(value) or value < 0: raise ValueError('Invalid stage duration')
        values = sorted(s['duration_ms'] for s in samples)
        def p(v): return values[math.ceil(len(values)*v)-1]
        stage_names = set.intersection(*(set(s.get('stage_ms', {})) for s in samples))
        return dict(count=len(samples), mean_ms=sum(values)/len(values), p50_ms=p(.5),p95_ms=p(.95),p99_ms=p(.99),
                    accuracy=sum(s['correct'] for s in samples)/len(samples), failure_rate=sum(not s['correct'] for s in samples)/len(samples),
                    calls=sum(s['calls'] for s in samples), retries=sum(s['retries'] for s in samples),
                    stage_mean_ms={k:sum(s['stage_ms'][k] for s in samples)/len(samples) for k in sorted(stage_names)})
    before, after = stats(baseline), stats(candidate)
    return dict(conditions=baseline['conditions'], baseline=before, candidate=after,
                mean_improvement_percent=(before['mean_ms']-after['mean_ms'])*100/before['mean_ms'] if before['mean_ms'] else None,
                confidence_limit='10 samples is the minimum; P95/P99 are descriptive order statistics, not reliable tail estimates or causal proof. JVM microbenchmarks do not establish Android or Voice E2E gains.')


def report(selection, evidence, sha, code, xml_directory):
    if sha != git('rev-parse', 'HEAD'):
        raise ValueError('Reports must identify the checked-out source actually tested, not another --head')
    results = xml_results(xml_directory)
    missing = [name for name, minimum in selection['required_jvm_classes'].items()
               if results['classes'].get(name, 0) < minimum]
    missing += selection['unresolved_selectors']
    historical = []
    for record in evidence:
        try:
            delta = changes(record['source_commit'], sha)
            r = reuse(record, delta, sha, code)
        except (ValueError, subprocess.CalledProcessError):
            r = dict(record, status='NOT_TESTED', current_version_acceptance=False, reuse_reason='Historical provenance/diff unavailable; no automatic reuse')
        historical.append(r)
    automation = results['passed'] and not missing
    config = load_config()
    environment = dict(runner_os=platform.system(), architecture=platform.machine(),
                       execution='GitHub Actions' if os.environ.get('GITHUB_ACTIONS') == 'true' else 'local JVM report import',
                       github_run_id=os.environ.get('GITHUB_RUN_ID'), github_job=os.environ.get('GITHUB_JOB'))
    automatic_evidence = []
    for group in selection['groups']:
        selectors = config['groups'][group]['jvm']
        expected = {name: minimum for name, minimum in selection['required_jvm_classes'].items()
                    if any(fnmatch.fnmatchcase(name, pattern) for pattern in selectors)}
        actual = {name: results['classes'].get(name, 0) for name in expected}
        group_missing = [name for name in expected if actual[name] < expected[name]]
        group_cases = {key: status for key, status in results['cases'].items()
                       if any(key.startswith(name + '.') for name in expected)}
        passed = bool(expected) and bool(group_cases) and not group_missing and not any(t in selection['unresolved_selectors'] for t in selectors) and all(v == 'PASS' for v in group_cases.values()) and not results['malformed'] and not results['duplicates']
        automatic_evidence.append(dict(test_id='jvm-group-' + group, issues=config['groups'][group]['issues'], feature=group,
            source_commit=sha, apk=dict(version_code=code, signature_status='NOT_SIGNED: JVM source tests',
                                      expected_production_signer_sha256=config['production_signer_sha256']),
            environment=environment, input=dict(selectors=selectors, required_class_test_counts=expected),
            expected='Every selected current-source class and at least its declared @Test count executes, with zero failure/skip/error',
            observed=dict(class_test_counts=actual, missing_classes=group_missing, cases=group_cases),
            raw_logs=results['raw_logs'], status='PASS' if passed else 'FAIL' if any(v == 'FAIL' for v in group_cases.values()) else 'NOT_TESTED',
            current_version_acceptance=False, scope='JVM only; no signed APK/device/UI acceptance'))
    return dict(source_commit=sha, version_code=code, environment=environment, selection=selection, jvm=results,
                missing_classes_or_selectors=missing, automatic_evidence=automatic_evidence,
                historical_evidence=historical, automation_passed=automation,
                ui_status='NOT_TESTED: use exact-SHA Premium UX UI job artifact',
                physical=[dict(p,status='NOT_TESTED') for p in selection['physical']],
                release_eligible=False,
                release_gate='Report is evidence, not release authorization. Require full exact-SHA PR/main CI, UI job, original signer/public APK verification. Physical NOT_TESTED never becomes PASS.')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest='command', required=True)
    for command in ['plan','report']:
        p=sub.add_parser(command); p.add_argument('--base',required=True); p.add_argument('--head',default='HEAD'); p.add_argument('--output',type=Path,required=True)
        if command=='report': p.add_argument('--xml',type=Path,default=ROOT/'app/build/test-results/testDebugUnitTest')
    p=sub.add_parser('performance'); p.add_argument('input',type=Path);p.add_argument('--output',type=Path,required=True)
    args=parser.parse_args(); config=load_config()
    if args.command=='performance':
        cohorts=json.loads(args.input.read_text()); result=performance(cohorts['baseline'],cohorts['candidate'])
    else:
        sha=git('rev-parse',args.head); base=git('rev-parse',args.base)
        if not SHA.fullmatch(sha) or not SHA.fullmatch(base): raise ValueError('Commit SHA could not be resolved')
        selection=plan(changes(base,sha),config)
        if args.command=='plan': result=selection
        else:
            props=dict(line.split('=',1) for line in git('show', sha + ':gradle.properties').splitlines() if '=' in line and not line.startswith('#'))
            result=report(selection,config['historical_evidence'],sha,int(props['VERSION_CODE']),args.xml)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    md=args.output.with_suffix('.md')
    if args.command=='report':
        md.write_text('# Acceptance evidence\n\n'+f"Source `{result['source_commit']}`, versionCode {result['version_code']}.\n\nJVM automation: {result['automation_passed']}; tests {result['jvm']['count']}, failures {result['jvm']['failed']}, skipped {result['jvm']['skipped']}.\n\n"+'\n'.join(f"- #{p['issue']}: {p['check']} — NOT_TESTED" for p in result['physical'])+'\n\n'+result['release_gate']+'\n',encoding='utf-8')
    if args.command=='report' and not result['automation_passed']: raise SystemExit(1)

if __name__=='__main__': main()
