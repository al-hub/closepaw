import copy
import hashlib
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location('release_input', pathlib.Path(__file__).parents[1] / 'verify-release-input.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReleaseInputTest(unittest.TestCase):
    def setUp(self):
        self.run = dict(id=123, head_sha='a' * 40, head_branch='main',
                        conclusion='success', status='completed',
                        path='.github/workflows/security-ci.yml', event='push',
                        repository={'full_name': 'al-hub/closepaw'},
                        head_repository={'full_name': 'al-hub/closepaw'})
        self.apk = b'unsigned optimized APK'
        self.metadata = dict(source_commit='a' * 40, run_id='123', version='0.1.75',
                             version_code='76', sha256=hashlib.sha256(self.apk).hexdigest())

    def check(self, run=None, metadata=None, apk=None):
        return module.verify(run or self.run, metadata or self.metadata,
                             self.apk if apk is None else apk,
                             'al-hub/closepaw', 'a' * 40, '0.1.75', '76')

    def test_trusted_exact_run(self):
        self.assertEqual(self.metadata['sha256'], self.check())
        for event in ('workflow_dispatch', 'schedule'):
            self.check(run={**self.run, 'event': event})

    def test_fail_closed_on_provenance(self):
        for field, value in [('head_sha', 'b' * 40), ('head_branch', 'feature'),
                             ('event', 'pull_request'), ('conclusion', 'failure'),
                             ('status', 'in_progress'), ('path', 'another.yml'),
                             ('id', 124), ('repository', {'full_name': 'fork/repo'}),
                             ('head_repository', {'full_name': 'fork/repo'})]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.check(run={**self.run, field: value})

    def test_reject_wrong_version_run_hash_and_modified_apk(self):
        for key in self.metadata:
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.check(metadata={**self.metadata, key: 'wrong'})
        with self.assertRaises(ValueError):
            self.check(apk=self.apk + b'tampered')

    def test_missing_repository_rejected(self):
        run = copy.deepcopy(self.run)
        del run['head_repository']
        with self.assertRaises(ValueError):
            self.check(run=run)
