# #219: bounded history and active SQLite verification

## Confirmed code paths

The pre-v107 JSON store refused a 2001st retained task in `capacity_check`,
including loading an existing ledger. PR224 replaced Android admission with
SQLite. v108 device evidence establishes new durable claims and Termux output;
it does not establish every original record's preservation.

The remaining diagnostic defect has two independently reproducible triggers:
`OperationTaskDiagnosticScanner` counted all payload nodes across the entire
history against 262144, and refused a 2001st historical task. Both can reject
valid data below the 16 MiB file bound. The diagnostic also inspected only the
legacy JSON while Android actually used SQLite. A `diagnostic_structure_limit`
therefore did not establish a current SQLite write failure or corruption.

## Change and invariants

- Keep strict UTF-8, 16 MiB legacy byte bound, per-record depth/node/key/string
  bounds, duplicate task/request/field rejection. Reset structural budgets for
  each historical record; historical count is not the old admission quota.
- Initial SQLite import consumes one complete record at a time (4 MiB character
  materialization bound), verifies indexed identity/order and full JSON,
  commits rows and schema together, then independently streams the original
  against committed rows before publishing the fsynced activation marker.
- Preserve the original JSON bytes. A committed database without its marker
  must exactly match the migration input before activation. A missing activated
  database, changed original digest, malformed source or failed durable boundary
  fails closed. An unexpected legacy overlay requires explicit migration.
- Reconcile interrupted work one record at a time in a transaction. Retained
  shell result scanning keeps result payload memory bounded to a record; its
  returned identity map still grows with the number of retained shell results.
- Passive diagnostics open SQLite with `OPEN_READONLY`, never create a store,
  execute schema DDL, reconcile, claim, repair or replay. Inspect quick_check,
  indexed identity/state/order and complete legacy records in stable order.
  Only exact records and the exact documented restart-to-outcome_unknown
  transformation are classified as verified. Other changes are `not_proven`,
  not silently accepted as preservation. No payloads/IDs/commands are returned.

## Evidence interpretation

`legacy_integrity=verified_current` means the original available **now** matches
its v107 activation digest and the currently observed records. It cannot
retroactively establish a hash before the original v107 upgrade. Report
`pre_upgrade_integrity=not_available` explicitly.

Diagnostics remain point-in-time observations; concurrent writes can cause a
conservative refusal or changed-content observation. `write_health=not_tested`
is intentional: a passive read is not a successful claim/fsync/commit. Verify
writes with one fresh harmless request, confirmed completion/readback and
same-ID dedup. Never rerun unknown historical work.

The fixed byte and per-record limits apply to the immutable legacy migration
input, not lifetime SQLite history. Indexed get/byRequest/claim avoid loading
the complete history; full audit takes time proportional to retained data.

## Required validation

RED production regressions are in
`OperationTaskHistoricalDiagnosticRegressionTest`, first submitted separately
on PR227. GREEN must be observed on the final code, not inferred.

JVM tests cover exact-byte hashing, complete record delivery, source replacement,
duplicates, malformed and per-record bounds. Android instrumentation covers
>12 MB/2001-record streaming import, every legacy record equality, new claims,
read-only diagnostics, changed-result refusal, rollback/no marker, missing DB,
concurrent connections, restart/unknown/dedup and existing SQLite compatibility.
The full Security/UI workflows retain their existing gates. Emulator evidence
does not establish handset history integrity or physical installation.

Independent final-head review, actual required GitHub approvals, Security/UI CI,
main CI, existing-key signing and public artifact verification remain release
gates. Keep #219 OPEN until actual handset diagnostic and functional acceptance;
do not modify #217 Bridge recovery as part of this change.
