# #219 task admission diagnosis — diagnostic release

The installed v0.1.104/code105 responds to passive reads but rejects new tasks before execution. The actual phone cause is UNCONFIRMED. MAX_TASKS=2000 is a code limit, not device saturation evidence. Do not infer an atomic-move or disk failure from a generic task_storage_failed.

## Diagnostic path

Discover and query `closepaw.task_store.diagnostics` with its current schema hash and empty input. The handler does not construct OperationTaskStore or TermuxJobLinkStore, take a write lock, create a task, or modify the ledger. Task/shell stores initialize lazily. Failed startup reconciliation remains fail-closed; a live store is never returned until queued/running tasks have been durably classified outcome_unknown.

Output contains bounded counts, max count, file bytes, usable bytes, read state, write_health=not_tested and recovery_attempted=false. Full task IDs, request IDs, fingerprints, commands, results, paths and exception messages are excluded. Input bytes, JSON depth and structural nodes are bounded during streaming; no payload DOM is built. Oversized/complex ledgers are refused without replacement. Snapshot readability does not prove a claim, fsync, lock or rename can succeed.

When a store access fails, last_failure_stage/reason retain only constant classifications in this process. They are not persistent historical evidence, and absent fields do not prove previous storage health. Stages include canonical path, directory, open/acquire lock, read/decode/record validation, capacity, staging, write, fsync and atomic replace. OS messages are inspected locally only to classify no_space/access_denied; no message is returned. OS lock acquisition is nonblocking.

Pre-claim dedup read errors return task_storage_failed with operation_started=false, rather than escaping into JSON-RPC invalid arguments. Runtime handles only typed store exceptions. A client/connector may still independently wrap MCP tool failures as INVALID_ARGUMENT; inspect the raw returned storage error. No client wrapper fix is claimed.

## Safety and remaining work

This diagnostic release does not claim a root-cause fix or automatic recovery. It changes no quota, deletes/archives no record, creates no alternate replay path and does not fall back to non-atomic writes. Existing task/result/dedup semantics remain. #217 daemon implementation is untouched.

After installation, first capture passive device diagnostics. Implement a recovery action only for the confirmed cause, preserving all active/unknown records and dedup. Such an action must have independent admission, durable bounded recovery state and independent verification; no unconditional retry/reset/reinstall. Capacity recovery may require a separately reviewed retained-result archive; it is intentionally not guessed here.

The current APK cannot install an update through a failed task claim. An install request, if attempted once, must not be repeated. A physical Android install approval/manual update may be required. Only after cause-specific remediation submit one new harmless Termux command and verify final exit0/stdout, then start/observe/end an explicitly authorized control session. #219 and #217 remain OPEN until these conditions pass.

## #221 bounded streaming follow-up (0.1.106/code107)

The measured 12,901,900-byte ledger exceeds the original 8MiB diagnostic guard, not an established admission/write limit. The diagnostic alone now permits at most 16MiB (plus one sentinel byte read), with strict UTF-8 decoding. A streaming JSON scanner validates syntax while discarding result/command/other payload strings and numeric tokens. It keeps only bounded identity sets and aggregate states; no DOM or whole-file byte/string copies are made.

Independent bounds: depth32 (including value depth), 262,144 value/key nodes, 2,000 tasks, 4,096 decoded characters per captured key/identity/state, 262,144 aggregate captured identity/state characters, 1,024 top-level keys and 65,536 key characters per task. Excess is a constant bounded refusal, not a truncated or partial count. Duplicate task/request identities and duplicate task fields are invalid; unknown/missing/null states count as unknown, active and terminal classifications retain their prior meaning. Optional missing/null request IDs remain absent. Non-string identities/states are invalid. JSON escape hex digits must be ASCII; malformed/trailing JSON and invalid UTF-8 fail closed. Large ignored output strings are scanned without retention.

The private app ledger must be regular. Size, modification time and file identity are compared before/after the descriptor scan; observed mutation/replacement returns ledger_changed without counts. Normal writers use atomic replacement; the old or new immutable snapshot is safe to read. Attribute checks cannot establish write health or defend arbitrary hostile in-place edits restoring identical metadata. No lock, task initialization, write, archive, deletion, recovery or daemon change occurs. Work is O(min(file bytes,16MiB)) with fixed parser buffers, depth-bounded stack and bounded metadata sets; elapsed time on the handset remains unmeasured. Tests stream large fixtures and hash before/after rather than keeping duplicate 13MB strings/DOMs. Root cause remains UNCONFIRMED and automatic recovery NOT_IMPLEMENTED.
