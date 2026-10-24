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
