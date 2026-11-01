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


## 2026-11-01 minimal implementation correction — supersedes custom generations

Decision: retain the existing `OperationTaskStore` API and its caller semantics, but replace the PR-only JSON history overlay rather than extending it. Use the platform `SQLiteOpenHelper`, a single `tasks` table, and unique `task_id` / `request_id` indexes. Columns needed for indexed filtering: `task_id`, `request_id`, `state`, `operation_id`, `created_at_ms`; keep the *complete* original JSON record in `payload_json`. No Room/ORM, multiple databases, custom generation manifest, archive scheduler or background replay. SQLite row data and indexes must commit atomically in one transaction; foreign work execution remains outside this transaction and therefore unknown outcomes MUST NOT be replayed.

**Do not translate existing legacy data without a preflight proof:** a bounded read-only validator must establish actual record count, unique IDs/requests, acceptable structure, disk headroom and original file hash. On first migration, populate SQLite under a transaction, verify complete semantic record equality and identity indexes, set a schema/migration marker in the same transaction and commit; never remove or mutate the legacy ledger. With an existing committed DB marker, do not silently reimport the legacy file even if the DB is missing/corrupt. Fail closed when marker/DB provenance or reconciliation is uncertain.

Keep original legacy data queryable until migration success; active/queued/running/cancel_requested become `outcome_unknown` durably on restart *before* any new work admission. Import old fields without dropping unknown keys or results. Preserve tie ordering for latestOutcomeUnknown with a sequence/order column if needed. Use one SQL write transaction per claim or state/result transition and enforce global request ID uniqueness atomically. Document a real device migration rollback/recovery contract; SQLite durability does not make external Termux commands transactional.

**Deliberate limits:** the original 2,000-task aggregate cap must disappear, but disk space, max per-record payload, maximum active-task population and query pagination remain bounded based on explicit measured policies. Do not claim unlimited throughput or fixed high-record count without stress evidence.

**Testing contract:** preserve baseline RED 2000→2001, then GREEN in the new engine; assert legacy SHA unchanged, every JSON identity/output preserved, idempotent migration, duplicate request rejection (including concurrent processes), unknown states, crash at pre-commit and post-commit boundaries, corrupted/missing DB refusal, same-state summary updates and large history performance. JVM-only tests can validate pure data contracts; actual Android SQLite/WAL migration tests require Android instrumentation or a sufficiently realistic SQLite host. Do not mask host Android stub failures with mocked success.

**Release gate:** confirmed live-handset cause/count, final-head Security + UI CI, independent code/security review, isolated Debug handset acceptance and single production-signed Release. PR #224 stays Draft, #219/#217 OPEN until verified. No unreviewed prod data modification.
