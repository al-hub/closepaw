# #219 — Lossless retained Task Store migration contract (approved 2026-11-01)

Status: design and RED gate only; **not deployed**. No production ledger was read, altered, exported, or migrated.

## Evidence and uncertainty
- Handset v0.1.106/code107, ledger 12,901,900 bytes, diagnostic bounded_refusal/diagnostic_structure_limit.
- Independent passive diagnostic in current process recorded last_failure_stage=capacity_check, last_failure_reason=invalid_data_or_limit.
- Production OperationTaskStore.insert checks size < 2000 (or replacing same task), also during load validation; dedup checks request_id.
- Actual handset count, exact failing request and initial cause still **unconfirmed**. A 2000-item fixture reproduces production capacity failure but is not proof of handset count.
- PR #223 isolated baseline boundary tests and Security/UI CI succeed. Neither constitutes a fixed regression.

## Approved retention invariants
1. Keep every original task_id, request_id, fingerprint, operation_id/version, created timestamp, result including stdout/stderr and all unknown fields byte- or semantic-equivalent across migration. Legacy getTask, byRequest, latestOutcomeUnknown, retainedShellLinks and pagination still retrieve retained tasks.
2. No forgotten dedup key: global request_id uniqueness/conflict detection spans active + history. No historical ID reuse, even after process death or upgrade.
3. All queued/running/cancel_requested tasks transition durably to outcome_unknown at restart *before* store admission; no automatic execution/replay. Existing outcome_unknown preserved.
4. A historical task is immutable only once terminal, but cannot move if any downstream shell job or cancellation/result update requires mutability. Terminal result/link query consistency across tiers is mandatory.
5. Never mutate/delete the original ledger before validating a complete and independently durable replacement plus recoverable manifest. Existing data remains an authoritative fallback; bound temporary disk consumption and avoid sensitive logging.
6. All state transitions across tiers must be crash-consistent. Single-writer OS lock across all tiers; fsync payload, index, manifest and parent directory as required; atomically publish generation pointer. Interrupted migration must select last *complete* generation or fail closed without replay.
7. Avoid fixed total-history limit and quadratic scan/rewrite: index task ID and request ID, bounded pagination/compaction and retention generations. Enforce bounds on *mutable active* working set, not cumulative lifetime total. Historical output must remain readable; explicit error if disk/write verification cannot be guaranteed.
8. Fail closed on malformed JSON, duplicate identities, corrupt index, migration uncertainty, key mismatch, failed fsync/atomic move; no silent fallback to empty store or non-atomic replacement.
9. Any migration must preserve legacy record ordering for latestOutcomeUnknown tie behavior and expose safe redacted counters/stages.
10. Verify after migration: identical (task_id, request_id, state, fingerprint, output hash) multiset, full query/dedup, unknown protections, active updates, and rollback under injected failure at every durable boundary. Only then allow new admission. Never perform speculative migration on handset until counts/cause are established and backup/safe upgrade is validated.

## Proposed architecture (implementation requires passing RED/green gates)
- Legacy JSON is immutable migration input until committed manifest. Compute streaming canonical semantic hash/record counters without exporting payload.
- Generation directory contains segment files for retained immutable terminal history, mutable active ledger, persistent request+task identity index and manifest recording generation, checksums and commit marker. Avoid duplicating entire 12.9MB in RAM for migration.
- Inter-process transaction lock serializes migration, claim, update and recovery. Write new generation to separate paths, verify, fsync, atomically publish the manifest pointer. Recover via verified generation or original unchanged file; fail closed if neither verified.
- New admission consults durable all-history dedup first and persists claim before execution. Historical query and shell-result linkage remain available. Unknown/cancel candidates stay in mutable tier.
- Never delete legacy file as a side effect of migration; deletion/compaction of verified historical payload is a *separate* future policy with review.

## Pre-release gates
A. Existing v0.1.106 production path: 1999→2000 success; 2000→2001 refusal, same-ID dedup read and original byte/hash preserved (PR #223).
B. A new **RED test** asserts that after a safe >2000-record import, a new ID may be claimed and all 2000 old requests/results are still queryable with dedup retained; expected to fail on current store.
C. Implement minimal reversible generation migration and make RED pass. Test large payload and node/depth-independent cases, two-instance concurrency, duplicate/corrupt data, crash at every write boundary and repeated restart, active/unknown and retainedShellLinks.
D. Independent code + security review, full changed-head CI, isolated Debug acceptance, source/tags integrity and one production-key signed Release only after physical cause is confirmed. Device first claim requires one harmless unique request and exit0/stdout evidence; no UI session unless separately authorized.
E. Keep #219/#217 OPEN until real admission and daemon acceptance respectively.

**Not allowed:** quota bump as repair, truncation, clearing app data, blind archive, deleting old ledger, replaying unknown work, silent partial migration, repeated signed APK attempts.
