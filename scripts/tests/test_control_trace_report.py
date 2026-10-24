import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('trace_report', Path(__file__).parents[1]/'control_trace_report.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class TraceReportTest(unittest.TestCase):
    def fixture(self):
        return dict(clock_domain='monotonic_process', total_ns=100,
                    spans=[dict(id=0,parent=-1,phase='action',start_ns=10,duration_ns=80),
                           dict(id=1,parent=0,phase='wait',start_ns=20,duration_ns=60)])

    def test_nested_wait_is_not_double_counted(self):
        out = m.summarize(self.fixture())
        self.assertAlmostEqual(sum(out['exclusive_ms'].values())+out['untraced_ms'],out['total_ms'])
        self.assertEqual(out['exclusive_ms']['action'],20/1e6)
        self.assertEqual(out['voice_e2e'],'NOT_TESTED')

    def test_wrong_clock_duplicate_or_crossing_parent_rejected(self):
        for mode in range(5):
            t = copy.deepcopy(self.fixture())
            if mode == 0: t['clock_domain']='wall_clock'
            if mode == 1: t['spans'].append(dict(t['spans'][0]))
            if mode == 2: t['spans'][1]['parent']=1
            if mode == 3: t['spans'][1]['duration_ns']=100
            if mode == 4: t['spans'].append(dict(id=2,parent=0,phase='other',start_ns=30,duration_ns=10))
            with self.assertRaises(ValueError): m.summarize(t)
