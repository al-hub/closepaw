import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('control_timing', Path(__file__).parents[1] / 'control_timing_report.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class ControlTimingReportTest(unittest.TestCase):
    def fixture(self):
        return dict(clock_domain='android_elapsed_realtime_ns', buffer_complete=True, dropped_events=0,
            events=[dict(event=name, id=1, step=1, at_ns=at) for name, at in [
                ('PROBE_QUEUED', 0), ('PROBE_SLOT_READY', 400000000), ('MAIN_ENTERED', 410000000),
                ('PROBE_DISPATCH', 412000000), ('PROBE_CALLBACK_OK', 432000000)]])

    def test_dispatch_is_distinct_from_main_and_slot_timing(self):
        report = m.summarize(self.fixture())
        probe = report['probes'][0]
        self.assertEqual(probe['slot_wait_ms'], 400)
        self.assertEqual(probe['main_queue_ms'], 10)
        self.assertEqual(probe['main_before_dispatch_ms'], 2)
        self.assertEqual(probe['api_callback_ms'], 20)
        self.assertEqual(report['speedup'].split(';')[0], 'NOT_COMPUTED')

    def test_wrong_clock_truncation_reversal_duplicate_and_boolean_fail_closed(self):
        for mode in range(6):
            raw = self.fixture()
            if mode == 0: raw['clock_domain'] = 'monotonic_process'
            if mode == 1: raw['dropped_events'] = 1
            if mode == 2: raw['buffer_complete'] = False
            if mode == 3: raw['events'][-1]['at_ns'] = 1
            if mode == 4: raw['events'].append(copy.deepcopy(raw['events'][-1]))
            if mode == 5: raw['events'][0]['at_ns'] = True
            with self.assertRaises(ValueError): m.summarize(raw)

    def test_missing_callback_and_cancel_are_not_success(self):
        raw = self.fixture()
        raw['events'][-1]['event'] = 'PROBE_CANCELLED'
        probe = m.summarize(raw)['probes'][0]
        self.assertEqual(probe['callback'], 'NOT_OBSERVED')
        self.assertIsNone(probe['api_callback_ms'])
        self.assertTrue(probe['cancelled'])
