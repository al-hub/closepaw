# Development continuation gate

## Goal
Replace 1-hour polling with an event-driven, exact-SHA handoff for draft PR #143.
The existing `signed-release.yml` already releases **only after a successful main Security CI**.
Do not weaken that trust boundary.

## State transitions
1. Draft PR #143 changes → Security CI and Premium UX UI run.
2. `development-continuation.yml` receives workflow_run completion on the default branch.
3. Compare the triggering HEAD with current PR HEAD. Fetch both completed successful PR runs for the exact SHA. Ignore stale, failed, in-progress, forked, merged, and non-draft PRs.
4. Leave exactly one marker comment for that HEAD. A new commit is a new SHA and must pass both gates again.
5. An **externally connected, authorized developer agent** may consume the marker and continue #126 → #140 → #142 using TDD/SRP. GitHub Actions does not itself supply an autonomous coding agent, device access, or credentials. No assertion of autonomous development is valid without an actual agent consumer.
6. After all code reviews and physical acceptance gates pass, PR can be reviewed/merged. Main Security CI triggers existing signed release and public APK checks. Installing and physically validating on Samsung remain separate.

## Security and limitations
- The workflow only reads Actions/PR metadata and posts an idempotent PR comment, never runs untrusted PR code with write credentials.
- `workflow_run` gate is defined on default branch only after its own reviewed merge. Until merged, its event-based operation is **not active**.
- Does not auto-merge, auto-version, auto-publish, auto-install or bypass lock-screen policy.
- No secret is needed for the gate. If an agent runner is connected in future, scope its credentials, filter issue/PR and SHA, prevent concurrent work, and prohibit self-approval and premature publishing.
- GitHub Actions event delivery can be delayed; it replaces scheduled polling, but is not a guaranteed sub-minute SLA.
