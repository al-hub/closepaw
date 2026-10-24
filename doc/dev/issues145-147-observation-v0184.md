# #145/#147 observation diagnosis — candidate v0.1.84/code85

## Performance investigation: what the existing trace permits

Historical code81 task834ff88f-b399-4f43-81f0-a8d2b01171ce remains REUSED_EVIDENCE.
The validated139-span trace has local total10953.098164ms, exclusive API spacing
6439.104063ms, and28 security_probe/28 probe_api_spacing spans: initial1 plus three
fresh security stages for each of9 actions. The existing sequence is already local
batching; post-observation is already event-driven. Intent waits count toward the
next screenshot gap, so deleting96ms cues need not improve elapsed time.

The newly audited probe count matters:27 nominal inter-probe gaps times400ms is
10,800ms of policy interval budget. This is contract arithmetic in Android
SystemClock.elapsedRealtime, not a measured execution duration or speedup bound.
Do not subtract it from the trace's System.nanoTime-derived duration. Trace spacing
SPAN EXIT gaps are399.814609..403.982774ms (mean401.193050); these are not direct
API dispatch timestamps. See the source-hashed [audit](evidence/issue147-probe-budget-20261025.json).

Inference: a large improvement requires a reviewed redesign of fresh-security
stage dependencies, not merely optimizing tree arithmetic inside the existing
28-probe/400ms contract. No rate reduction, security verdict reuse, check removal,
coordinate shortcut, speculative batching or performance acceptance claim is made.
Actual candidate speedup, matched10+10 and Voice/AI/transport timing remain NOT_TESTED.
The user-authorized maximum3 simplified new physical performance trials does not
replace the strict statistical criterion. No such trial is run in this work.

## Concrete diagnostic defect and limited correction

GeminiReplyCollector checked an unverified/changed snapshot BEFORE rechecking
cancellation, allowed permission/session and lock after its suspended read. Thus
revocation/lock/cancel concurrent with a false-foreground snapshot could be
misclassified as target_changed. Reorder only these failure checks; every branch
still refuses execution/completion. No settling retry, resend, target acceptance
relaxation, stale-data reuse or session restart is introduced.

This counterexample does not establish the cause of the historical code84 task
9cf42b92... or its failure frequency. Earlier FAIL/unknown and later single PASS
remain separate. Add a fixed observation_reason vocabulary to distinguish revoked
observation, locked device, missing root, Google-hosted composer absence, wrong
package and existing native/hosted recognition. Preserve the existing target policy
(including its hosted composer rule); do not silently turn diagnostic labels into
new permission. Unverified screens still export no nodes or raw content.

Inspection exceptions return only a fixed reason code for security/argument/state/
other failure. Exception messages, arbitrary types, stack traces, question/reply
content and unknown strings are not exposed. Structured cancellation still propagates.

## Tests and acceptance boundaries

Seven new JVM regressions: three mid-read revocation/lock/cancel races; real adapter
projection across missing root/composer/wrong package/recognized host; early-guard
no-read behavior; allowlisted metadata/privacy; fixed exception classification/privacy.
Existing target-switch, cancellation, completion and no-write tests remain.
Local Python39 and diff check PASS. Local Kotlin/Android execution remains unavailable
because the previously observed Gradle distribution download is unreachable; no
local RED assertion/compilation/PASS is claimed. Exact-source CI is required.

This is an executable diagnostic correction, hence a new version/code and normal
PR/main/security/UI/original-key public-release verification are required. Do not
move the already publishedv0.1.83 tag or reuse its assets as candidate proof. Installation
must be submitted once. Only a read-only installed-candidate diagnosis is needed;
do not repeat successful question/X/unlock/rotation/drag tests. #145/#147 stay OPEN
until their actual remaining acceptance conditions pass; #146/#150 remain CLOSED.

## Release and independent CI verification (2026-10-25)

PR158 exact headb4323aa9db628f893e4f016edfaa2272378e2dc8: Security40142089464/
UI40142089520 success. Independent raw XML:2750 JVM (all7 new named regressions
present),11 UI, failure/error/skip0,8 PNG outputs. PR XML12579765129 SHA256
9cb21578aae4e74691576e005d45aa0876e27a3c8585887150a83a51dc1a35d9;
PR UI12578919739 SHA256c00669422c4c84809b51e5554b8d32d9a050ec334d7d68a2758c716c2b6261f4.

Merged main/APK/tag sourcefe5863bfea48a323431db1eca8bb6501cad2ab26.
Main Security40142615725/UI40142615660/Signed40143180227 success. Independent
main raw XML2750 JVM/11 UI failure/error/skip0,8 PNG outputs. Main XML12579426863
SHA256458ef7e6ebfa5d0ccc1e0aeac8673ae4921b0fbedacb4d3fb4008d64e3ff4647;
main UI12579546453 SHA25677a4be09dc12da4d7acbdf9002ab3ffeba5e5d357a38efd45c573e43e35f0df5.
Public CI verification artifact12579038703 SHA256
823ccd2eb5811f7578d6d8050850f690d909fa78bcead19cf518319f1507d37a.

[Release0.1.84/code85](https://github.com/al-hub/closepaw/releases/tag/v0.1.84):
116642415bytes, SHA256ea2cdd46796d68a73b6444829109fb8015a8b9ad82a7e20125ab0f14dc57f859.
Anonymous public APK/checksum independently downloaded. Existing operating signer
SHA256d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba,
v3 RSA-SHA512 signature and full chunked content digest, binary manifest
ai.closepaw/0.1.84/85, arm64 openaitunnel/cloudflared, annotated tag
da947507cbd058da15de5602d64d5b9cfc0233bc→sourcefe5863... independently PASS.

Installation submitted exactly once: taskcd1b5b2d-df67-4e80-8027-187f6d82f1d9.
Its durable installed/current and independent connectivity results are recorded
separately below; accepted/installing alone is not installation acceptance.

## Installed read-only diagnosis and confirmed follow-up contract defect

Durable installed/current0.1.84/code85 and independent reachable status PASS.
New read-only diagnostic sessionc365743f-2eb2-4525-aa8f-9041cce571f8, start task
0d697be0-0d6a-4626-9a92-e4c351128d53. One gemini.ui.inspect returned **FAIL** with
snapshot_illegal_state. This confirms the new bounded exception code is emitted,
not successful Gemini observation. No question, launch, text input or retry occurred.
End task02125429-2d1d-4166-a39f-2d6d37688140 success, independent ended/epoch2.

Static call-chain comparison identified a deterministic contract defect:
gemini.ui.inspect accepts only{}, but its Android runtime calls
screenMutex.withRemoteActivity() with the default null sessionId. The explicit
active session guard requires the actual ID and throws IllegalStateException via
session.refusal(null). Thus this inspection path cannot pass the guard in an
explicit session. This explains the contract-level inspection refusal; it is not
proof of the cause of the separate historical question collector target_changed.

A follow-up must accept/forward a validated session_id and require an explicitly
active matching session without starting/resuming one. Preserve epoch checks and
wrong/missing/stale-token rejection; never bypass the shared guard. v0.1.84 contains
diagnostics only, so the contract correction requires its own new immutable version.
