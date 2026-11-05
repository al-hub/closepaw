# #225 Draft — bounded cold index for bridge overflow

**Current candidate, not operating-device recovery.** Keep the existing
`jobs/archive/*.json` (maximum 1000 old records) and all active/unknown
hot records unchanged. On the next hot-capacity admission *after the legacy
archive is full*, move one confirmed terminal hot JSON record to a
Termux-private SQLite cold index. The SQLite row stores the **exact original
JSON bytes**, a SHA-256 digest, job ID, owner, fingerprint, and request ID;
commit/full-sync and byte-for-byte query confirmation must precede removal
of the redundant hot JSON copy. Old 1000 JSON archive files are never purged.

A crash after SQLite commit but before JSON retirement leaves both copies.
On startup, duplicate copies are accepted only if the bytes match; divergent
duplicates or corrupt SQLite fail closed. Active, unknown and missing-result
records cannot be moved. A request ID in cold storage returns its original
job and cannot launch a second process. SQLite is capped at about 512 MiB
(`max_page_count`); reaching that bound is another explicit refusal,
not permission to delete history or grow without limit.

This draft requires full independent security review, Android/Termux daemon
deployment readiness, tests under concurrent requests and crash injection,
and real 2,000+ history physical acceptance. GitHub CI only checks host
fixtures. No Release/APK/daemon installation or real task submission has
been performed. Existing issue #215's accepted historical evidence remains
unchanged.

## Existing 3-file maintenance trust contract preserved

The APK/standalone bundle and local maintenance verifier intentionally retain
exactly `closepaw_jobs.py`, `closepaw_maintenance.py` and
`closepaw_bridge.py`. The cold index implementation is **inlined inside
`closepaw_jobs.py`** rather than adding a fourth pinned source. Existing
signed 3-file update policy and root/user trust boundary are not widened.
A locally authorized revision pin is still required; no remote request
self-authorizes it. In particular, app updating alone does not prove that
an already-running Termux daemon has switched revisions.

## Full-size output bound regression

A confirmed job may carry 1MiB each of non-UTF8 stdout and stderr. The daemon
preserves raw bytes in base64 and decodes invalid UTF-8 to replacement
characters for its JSON output; the JSON-escaped replacement characters
can expand each 1MiB to 6MiB *plus* 1.33MiB of base64. The earlier 4MiB
cold-row limit therefore refused legitimate terminal results at capacity.
The bounded row cap is now 16MiB, still within the 512MiB SQLite file bound,
without changing the command output size limits. A test spills exactly two
maximum-size invalid-UTF8 outputs and verifies original bytes, result lookup
and dedup after restart.

## Independent review corrections (2026-11-06 KST)

Independent Codex review of b6ca848 found two reproducible P1 defects: recreating
an absent cold database lost historical dedup, and admission could race a health
failure after its initial readiness check. Both new regressions fail against that
SHA. Admission now rechecks readiness under its transaction lock.

A separate owner-private `cold-records.witness` durably records a lower bound on
cold rows after SQLite commit/readback and before hot JSON retirement. An additional
`jobs-cold-history.anchor` in the parent directory preserves the bound even when
the DB and in-directory witness are lost together. Both private checkpoints are
fsynced before retirement; restart uses their maximum bound. Missing
DB/witness, fewer rows, or live DB replacement refuses admission; history is not
silently reinitialized. Checkpoint failure retains the hot copy. Crash after DB
commit but before checkpoint is safe because no hot copy was retired; startup
accepts extra valid rows and verifies equal hot/cold duplicates. Interrupted first
initialization may require local inspection; never remove the witness to recover.
This detects accidental loss, not coordinated malicious modification of every
owner-writable history file or a rollback of the complete parent directory.

The old default maintenance listener 18424 collided with Android's MCP listener.
Both Python supervisor and Android operation now use 18427 (jobs port +5), with
an occupied-old-port real-process regression and Android default-port test. A
production-signed APK update is therefore required for the Android client and
embedded bootstrap; an APK alone still does not prove the Termux daemon changed.
The existing three source pins and local approval boundary remain unchanged.
