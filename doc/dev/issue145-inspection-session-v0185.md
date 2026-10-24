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
