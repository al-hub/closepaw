import importlib.util
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

spec = importlib.util.spec_from_file_location('acceptance', Path(__file__).parents[1] / 'acceptance.py')
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)

def cohort(samples=None):
    return dict(source_commit='a'*40,
                conditions=dict(device='same_host', os='Linux', runtime='JVM17', configuration='warmup3',
                                workload='256 synthetic strings', execution_path='SHA256+hex', network='not_applicable',
                                scope='host_jvm_hex', clock_domain='process_monotonic'),
                samples=samples or [dict(duration_ms=1, correct=True, calls=1, retries=0)]*10)

class AcceptanceTests(unittest.TestCase):
    def test_forged_signer_and_boolean_version_reject(self):
        for changed in [dict(signer_sha256='0'*64), dict(version_code=True)]:
            record = dict(a.load_config()['historical_evidence'][0])
            record['apk'] = dict(record['apk'], **changed)
            with self.assertRaises(ValueError): a.validate_record(record)

    def test_boolean_duration_is_not_a_measurement(self):
        valid = cohort()
        bad = cohort([dict(duration_ms=True, correct=True, calls=1, retries=0)]*10)
        with self.assertRaises(ValueError): a.performance(valid, bad)

    def test_full_inventory_requires_each_class_and_test_count(self):
        config = a.load_config()
        inventory = {'ai.closepaw.ui.overlay.OldTest': 2, 'ai.closepaw.ui.overlay.NewTest': 3}
        planned = a.plan(['unknown.cpp'], config, inventory)
        self.assertEqual(planned['required_jvm_classes'], inventory)
        with tempfile.TemporaryDirectory() as tmp, patch.object(a, 'git', return_value='a'*40):
            Path(tmp, 'TEST-old.xml').write_text('<testsuite tests="1"><testcase classname="ai.closepaw.ui.overlay.OldTest" name="one"/></testsuite>')
            result = a.report(planned, [], 'a'*40, 81, Path(tmp))
            self.assertFalse(result['automation_passed'])
            self.assertIn('ai.closepaw.ui.overlay.NewTest', result['missing_classes_or_selectors'])
            self.assertIn('ai.closepaw.ui.overlay.OldTest', result['missing_classes_or_selectors'])

    def test_suite_counter_truncation_and_suite_error_reject(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp, 'TEST-corrupt.xml')
            p.write_text('<testsuite tests="2" failures="1"><testcase classname="x" name="one"/></testsuite>')
            self.assertFalse(a.xml_results(Path(tmp))['passed'])
            p.write_text('<testsuite><error message="runner died"/><testcase classname="x" name="one"/></testsuite>')
            self.assertFalse(a.xml_results(Path(tmp))['passed'])

    def test_report_rejects_unrelated_head(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(a, 'git', return_value='b'*40):
            with self.assertRaises(ValueError): a.report({}, [], 'a'*40, 81, Path(tmp))

    def test_selected_inventory_expands_wildcards(self):
        planned=a.plan(['app/src/main/kotlin/ai/closepaw/ui/overlay/X.kt'],a.load_config(),
                       {'ai.closepaw.ui.overlay.OneTest': 1, 'ai.closepaw.ui.overlay.NewTest': 2})
        self.assertIn('ai.closepaw.ui.overlay.NewTest', planned['required_jvm_classes'])
        self.assertEqual(planned['required_jvm_classes']['ai.closepaw.ui.overlay.NewTest'],2)

    def test_raw_artifact_missing_or_modified_reject(self):
        record=dict(a.load_config()['historical_evidence'][0])
        record['raw_logs']=['doc/dev/does-not-exist.json']
        with self.assertRaises(ValueError): a.validate_record(record)
        record=dict(a.load_config()['historical_evidence'][0]); record['raw_log_sha256']={}
        with self.assertRaises(ValueError): a.validate_record(record)

    def test_performance_requires_complete_conditions_and_source(self):
        valid=cohort()
        for changed in [dict(valid,source_commit='main'), dict(valid,conditions={'device':'same','scope':'hex','clock_domain':'process_monotonic'})]:
            with self.assertRaises(ValueError): a.performance(changed,changed)

    def test_unknown_change_expands_and_safety_is_always_selected(self):
        plan = a.plan(['unknown/native.cpp'], a.load_config())
        self.assertTrue(plan['full_jvm'])
        self.assertIn('security', plan['groups'])
        self.assertIn('cancellation', plan['groups'])
        self.assertEqual(len(plan['physical']), 3)

    def test_docs_only_does_not_claim_new_apk_acceptance(self):
        plan = a.plan(['doc/dev/notes.md'], a.load_config())
        self.assertFalse(plan['full_jvm'])
        self.assertEqual(plan['physical'], [])
        self.assertIn('security', plan['groups'])

    def test_overlay_selects_transitive_ui_and_safety(self):
        plan = a.plan(['app/src/main/kotlin/ai/closepaw/ui/overlay/compose/IslandOverlayHost.kt'], a.load_config())
        self.assertIn('geometry', plan['groups'])
        self.assertIn('lifecycle', plan['groups'])
        self.assertIn('cancellation', plan['groups'])
        self.assertEqual({x['issue'] for x in plan['physical']}, {145, 146, 147})

    def test_missing_xml_and_skip_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertFalse(a.xml_results(Path(tmp))['passed'])
            Path(tmp, 'TEST-a.xml').write_text('<testsuite><testcase classname="x" name="test"><skipped/></testcase></testsuite>')
            self.assertFalse(a.xml_results(Path(tmp))['passed'])

    def test_duplicate_xml_cases_are_not_double_counted(self):
        with tempfile.TemporaryDirectory() as tmp:
            for n in ['a', 'b']:
                Path(tmp, f'TEST-{n}.xml').write_text('<testsuite><testcase classname="x" name="test"/></testsuite>')
            self.assertEqual(a.xml_results(Path(tmp))['duplicates'], ['x.test'])
            self.assertFalse(a.xml_results(Path(tmp))['passed'])

    def test_dtd_xml_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, 'TEST-a.xml').write_text('<!DOCTYPE x [<!ENTITY e "x">]><testsuite/>')
            with self.assertRaises(ValueError): a.xml_results(Path(tmp))

    def test_historical_pass_never_promoted(self):
        record = a.validate_record(a.load_config()['historical_evidence'][0])
        out = a.reuse(record, ['doc/dev/notes.md'], 'a' * 40, 81)
        self.assertEqual(out['status'], 'REUSED_EVIDENCE')
        self.assertEqual(out['source_commit'], record['source_commit'])
        self.assertEqual(out['apk']['version_code'], 80)
        self.assertFalse(out['current_version_acceptance'])

    def test_influence_overlap_and_unbounded_scope_blocks_reuse(self):
        record = dict(a.load_config()['historical_evidence'][0])
        self.assertEqual(a.reuse(record, ['app/src/main/kotlin/ai/closepaw/chatgpt/control/GenericControlSequence.kt'], 'a'*40, 81)['status'], 'NOT_TESTED')
        record['dependencies'] = []
        self.assertEqual(a.reuse(record, [], 'a'*40, 81)['status'], 'NOT_TESTED')

    def test_evidence_requires_provenance_and_raw_log(self):
        record = dict(a.load_config()['historical_evidence'][0]); record['source_commit'] = 'main'
        with self.assertRaises(ValueError): a.validate_record(record)
        record = dict(a.load_config()['historical_evidence'][0]); record['raw_logs'] = []
        with self.assertRaises(ValueError): a.validate_record(record)

    def test_benchmark_rejects_small_unmatched_mixed_clock_or_nonfinite(self):
        valid = cohort()
        self.assertEqual(a.performance(valid, valid)['mean_improvement_percent'], 0)
        for invalid in [dict(valid, samples=valid['samples'][:9]), dict(valid, conditions=dict(valid['conditions'], device='other')), dict(valid, samples=[dict(duration_ms=float('nan'), correct=True, calls=1, retries=0)]*10)]:
            with self.assertRaises(ValueError): a.performance(valid, invalid)

    def test_failed_sample_kept_in_accuracy_and_statistics(self):
        valid = cohort([dict(duration_ms=i+1, correct=i!=0, calls=1,retries=0) for i in range(10)])
        out=a.performance(valid,valid)
        self.assertEqual(out['baseline']['accuracy'], 0.9)
        self.assertEqual(out['baseline']['p99_ms'], 10)
        self.assertIn('10 samples', out['confidence_limit'])

if __name__ == '__main__': unittest.main()
