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
