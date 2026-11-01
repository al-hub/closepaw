# #219 cause discrimination before remediation

Investigation base: `4c7ebf0544bb3a70333eaec3e74fbdf432456cec` (production code
identical to released `7ac9789d89fa43b9d76f1281d09ade16b04dc11c`).
This change contains synthetic tests and documentation only. It is not a repair,
a reproduction of the private handset ledger, or authorization to release.

## Live evidence and access boundary

The installed 0.1.106/code107 dual-version confirmation is reused from
[#219](https://github.com/al-hub/closepaw/issues/219#issuecomment-6555491752).
A fresh passive query in this investigation returned file_bytes=12901900,
max_read_bytes=16777216, diagnostic_structure_limit, usable_bytes=209870491648,
write_health=not_tested, write_test_performed=false, recovery_attempted=false.
No counts or last failure stage/reason were returned. No new task, installation,
UI session, repair, archive, deletion, or replay was submitted.

Available MCP operations do not expose the private ledger or the specific parser
bound. The existing diagnostic ZIP exporter includes traces and browser/Gemini/
projection diagnostics; it does not export mcp/tasks.json or its structure.
No ADB is available on this Work host. Do not request an ordinary diagnostic ZIP
as though it could resolve the missing task-store evidence. Do not upload the
private ledger to GitHub or public logs.

## Production call graph

- AndroidOperationRuntime lazily creates OperationTaskStore for mcp/tasks.json.
  The passive diagnostic uses the same path but does not construct this store.
- OperationDispatcher.execute validates input, then reads byRequest for dedup,
  then calls claim before launching the handler. Either store exception is
  collapsed to task_storage_failed. Store construction first reconciles active
  records to outcome_unknown durably; a failure prevents initialization.
- Every store transaction takes a JVM path lock and a nonblocking OS lock, loads
  the whole UTF-8 JSON DOM, validates unique task/request identities, and applies
  the requested operation. A new identity at 2000 records fails capacity_check.
  Existing IDs, results, and request dedup can remain readable at capacity.
- Mutations write a separate staging file, sync the file descriptor, then perform
  atomic replacement. There is no removal/retention API. Parent-directory sync
  is absent; existing tests inject before boundaries and do not establish
  post-rename power-loss durability. These are design observations, not the
  observed phone failure cause.
- TermuxShellOperation reaches TermuxJobLinkStore only after shared task claim.
  A link-store refusal cannot by itself explain a pre-handler refusal common to
  shell, UI, and updater operations.

## Why the current diagnostic cannot establish the cause

The diagnostic scanner independently limits depth, nodes, captured key/identity
length, per-task key totals, and aggregate retained identity/state characters.
All of these currently map to diagnostic_structure_limit. Production store load
does not enforce these scanner limits.

In particular, 1516 records with 36-character task IDs, 128-character request IDs
and 9-character succeeded states retain 262268 characters, above the diagnostic
262144-character bound but below the store's 2000-record admission capacity.
The dispatcher allows a 128-character request ID. Payload node/depth limits can
also refuse valid JSON independently of byte count and task capacity.

## Synthetic production-path harness

OperationTaskStoreInvestigationTest uses the unchanged production classes, with
six fixtures each exactly 12901900 bytes. Padding is an ignored string, while
the tested structural dimension changes independently:

| Synthetic fixture | Diagnostic expectation | New claim expectation |
| --- | --- | --- |
| One task, large result string | readable | succeeds |
| Payload exceeds scanner node budget | diagnostic_structure_limit | succeeds |
| Payload exceeds scanner depth budget | diagnostic_structure_limit | succeeds |
| Captured payload key exceeds scanner token budget | diagnostic_structure_limit | succeeds |
| 1516 records with production-valid long identities | diagnostic_structure_limit | succeeds |
| 2000 compact terminal records | capacity_reached | capacity_check refusal |

The harness checks byte size, diagnostic nonmutation/redaction, production
admission and state transitions, original record/result preservation, existing
request dedup, and unchanged bytes on capacity refusal. Existing diagnostic,
dispatcher and concurrency tests cover malformed/duplicate JSON, lock contention,
restart reconciliation, dedup races, and pre-boundary storage failure injection.
Reuse them; do not label them real handset or power-loss acceptance.

## Gates and next evidence

Local Gradle bootstrap failed before executing tests because services.gradle.org
was unreachable. Actual results must be read from the investigation PR's CI;
expected results above are not execution evidence.

Even a passing synthetic matrix only proves that the current phone observations
admit multiple explanations. It cannot supply gate A/B (same actual defect before
FAIL/after PASS). No production fix, retention-policy change, version increment,
main merge, or Signed Release is justified by this matrix alone.

The next required evidence is a non-destructive, private, read-only observation
of the actual structure and the actual admission failure stage. An existing
authorized device/ADB channel with access to that data could collect only
redacted aggregates. Merely enabling USB debugging does not grant access to a
non-debuggable release application's private directory. If no existing channel
can read it, a separately reviewed, isolated diagnostic approach is needed;
do not install a debug APK over the production app or publish another speculative
diagnostic Release. Root cause and device acceptance remain BLOCKED/UNCONFIRMED.
#219 and #217 remain OPEN; daemon/supervisor acceptance is separate.
