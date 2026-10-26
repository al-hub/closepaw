import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("release_checks", Path(__file__).resolve().parents[1]/"verify-release-checks.py")
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)

class ReleaseChecksTest(unittest.TestCase):
    def payload(self):
        return {"workflow_runs": [{"name": name, "path": path, "head_sha": "source", "head_branch": "main",
            "repository": {"full_name": "al-hub/closepaw"}, "event": "push", "status": "completed", "conclusion": "success"}
            for name,path in checks.REQUIRED]}
    def test_both_exact_main_workflows_are_required(self):
        p=self.payload();checks.verify(p,"al-hub/closepaw","source")
        p["workflow_runs"].pop()
        with self.assertRaises(ValueError):checks.verify(p,"al-hub/closepaw","source")
    def test_failed_or_running_ui_cannot_be_signed(self):
        for status, conclusion in [("completed","failure"),("in_progress",None),("completed","cancelled")]:
            p=self.payload();p["workflow_runs"][0].update(status=status,conclusion=conclusion)
            with self.assertRaises(ValueError):checks.verify(p,"al-hub/closepaw","source")
    def test_pr_wrong_source_repository_and_workflow_cannot_supply_release_authority(self):
        for change in [{"head_branch":"feature"},{"event":"pull_request"},{"head_sha":"older"},
                       {"repository":{"full_name":"other/repo"}},{"path":".github/workflows/other.yml"}]:
            p=self.payload();p["workflow_runs"][0].update(change)
            with self.assertRaises(ValueError):checks.verify(p,"al-hub/closepaw","source")
