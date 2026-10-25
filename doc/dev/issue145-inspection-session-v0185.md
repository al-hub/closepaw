# Gemini inspection session contract — candidate0.1.85/code86

The installed0.1.84 read-only inspection returned snapshot_illegal_state.
Static call-chain verification proves that the old inspect schema accepts only{},
while AndroidOperationRuntime calls withRemoteActivity() with null sessionId.
ControlSession.refusal(null) rejects every explicitly active session with
control_session_id_required before any snapshot is read. This is a deterministic
inspection admission defect, separate from the historical question target_changed.

Correction: inspect now requires a bounded nonempty session_id. The operation
validates schema and forwards the exact token; Android registration requires an
explicitly ACTIVE matching session before entering the existing mutex/epoch/session
guard and forwards that same token to withRemoteActivity(sessionId). Idle/ended/
paused/legacy/missing/stale/wrong sessions cannot trigger snapshot or implicit start.
No guard bypass, launcher, question send, resend, coordinate action, permission
change or exception-message exposure. Fresh discovery is required because the
input schema/hash changes; the connector itself does not need re-registration.

Three regressions cover malformed/missing input with zero reads, exact token
forwarding using the actual ControlSession admission helper, and rejection of
idle/ended/paused/stale sessions without state/epoch mutation. Existing privacy,
cancellation and guarded inspection tests remain. Local Gradle unavailability is
reused, not retried or labeled RED/PASS. Exact-source full CI remains required.

A second immutable app version is necessary because0.1.84 has already been signed,
published and installed with diagnostics only. Its tag/assets must not be moved or
overwritten. Its complete evidence is retained in issues145-147-observation-v0184.md
and evidence/issues145-147-observation-v0184-release.json. Apply the original-key
release/public verification/single-install procedure, then one read-only inspection
with the confirmed new session token. Do not repeat successful question/physical
X/unlock/rotation/drag tests. Do not claim a fix to the historical target_changed
or an Android/Voice performance improvement from this contract correction.

Exact-source results: NOT_TESTED until appended below. #145/#147 remain OPEN until
their remaining actual acceptance conditions pass; #146/#150 remain CLOSED.

## Release verification (2026-10-25)

PR159 exact headca15054799239461d60f1fa697102935f747580e: Security40143732746/
UI40143732679 success. Raw XML independently parsed2753 JVM (all3 new named
contract tests present),11 UI failure/error/skip0,8 PNG. PR JVM artifact12579433697
SHA2562cf4ae78908dacdd5cd6fea297a1dd62f0228af50492aaf7a0d1be9fbfdb8d00;
PR UI12579418924 SHA256952196e3f38a7c4b594db9a222d14cd93c51bbfede49bb7060ae6ba6a3884155.

Merged immutable APK/tag source8ed27ad6bb9cc97a6d28a21d8a560ed2ab150088.
Main Security40144294423/UI40144294444/Signed40144807816 success. Independently
parsed main XML2753 JVM/11 UI failure/error/skip0 and8 PNG. main JVM12580175792
SHA2563496e16a08adf5384b6d821652a39577f8edb9fb21e38cc06cd6ab79642bfd1d;
main UI12579713976 SHA25617b4dc68b7b54a7c559af6a8550e4ef89c5d7a483a9b7b8a19de940ac8331718.
Public verification12579564630 SHA256
556e18f6510b72a17e7321227f75333c3e9f07a438b740d5e570d414be9a67e7.

[Release0.1.85/code86](https://github.com/al-hub/closepaw/releases/tag/v0.1.85):
116646511bytes, SHA2565b8cb4f565fcd146e98adf5c9d13f31941f6ed49e9cfca3c64d5291fc4e9199b.
Anonymous public download/checksum, existing operating signer
SHA256d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba,
v3 RSA-SHA512 signature and full chunked content digest, binary manifest
ai.closepaw/0.1.85/86, arm64 openaitunnel/cloudflared and annotated
8b603f78c7518baf1336a3c3a3f967fd1c38f8ee→source8ed27... independently PASS.

Installation requested once: task900ccaa7-26f5-4574-b09d-ac0944e80231.
Durable installed/current and independent reachability are recorded below, not
inferred from queued/accepted/installing. No operating key/tag/old asset was changed.

## Installed candidate and safety stop — inspection acceptance NOT_TESTED

Durable self_update.status confirmed installed/current0.1.85/code86 for the single
request; independent get_status confirmed reachable0.1.85/code86. Installation PASS.
Fresh discovery confirms inspect now requires session_id and has schema hash
247a4fb017d9644ff194f3b44bc7849a81c0ee83776f009fdcdcd412ccc510d5.

Initial session ended/epoch0. This user's authorized new verification session was
started once: task63f1c551-31cb-4c16-a183-72c0e17f7ff0,
sessionf35daf6a-985f-4ba8-bb38-2f90a6e91586, active/epoch1 confirmed.
The one read_app(gemini) used to bring the target forward returned FAIL:
control_session_ended / User control has priority, screenshot_attempts0. Its
possible app-launch effect is unconfirmed; no question/text input/send was invoked.
Independent session.status confirmed the same session ended/epoch2. Whether a
physical X/user action caused termination was not measured. Do not invent that cause.

No inspect invocation followed this stop; no session restart, read retry or duplicate
end was sent. **Actual installed-candidate positive inspection remains NOT_TESTED**,
not PASS. The code-level session-contract counterexample is fixed and CI-proven;
its positive device path is not yet accepted. Respect the terminal session and
require a new explicit user request before any new UI work. Do not bypass this
refusal with another launcher/gesture/legacy session.

Previous Gemini single-roundtrip PASS and expanded emulator rendering are
REUSED_EVIDENCE at their original sources, not newly executed candidate question/
Galaxy panel PASS. No physical X/unlock/rotation/drag test repeated. #145 OPEN,
#147 OPEN (matched performance/Voice E2E and actual optimization unproven),
#146/#150 CLOSED unchanged. Next minimum device work is one newly authorized
active-session inspection with its current token; preserve earlier failed/unknown
results and do not resend their questions. Galaxy expanded panel can be observed
once during normal authorized use. No more signing/reinstallation is needed just
to validate the already installed candidate.
