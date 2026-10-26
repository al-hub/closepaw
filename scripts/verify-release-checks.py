#!/usr/bin/env python3
"""Require both successful canonical workflows on the exact trusted main commit."""
import json
import sys

REQUIRED = {
    ("Security CI", ".github/workflows/security-ci.yml"),
    ("Premium UX UI", ".github/workflows/premium-ux-ui.yml"),
}

def verify(payload, repository, sha):
    passed = set()
    for run in payload.get("workflow_runs", []):
        if (run.get("head_sha") == sha and run.get("head_branch") == "main"
            and run.get("repository", {}).get("full_name") == repository
            and run.get("event") in ("push", "workflow_dispatch", "schedule")
            and run.get("status") == "completed" and run.get("conclusion") == "success"):
            passed.add((run.get("name"), run.get("path")))
    if not REQUIRED.issubset(passed):
        raise ValueError("both exact-main Security CI and Premium UX UI success are required before signing")

if __name__ == "__main__":
    try:
        with open(sys.argv[1]) as source:
            verify(json.load(source), sys.argv[2], sys.argv[3])
    except (ValueError, KeyError, OSError, IndexError) as error:
        sys.exit(str(error))
