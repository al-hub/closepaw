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

## Codex coding worker (proposed alongside event gate)
`codex-continuation.yml` now provides a bounded continuous development pipeline:
1. On completed Security CI / Premium UX UI event, verify exact #143 draft head, same repository, and **both** green PR workflows.
2. Limit to **three** unattended agent commits on the PR. Later commits require review.
3. Run `openai/codex-action@v1` in a **read-only GitHub token** job. The Codex process may edit only its ephemeral workspace; no push token is available to it. Artifacts contain a patch, not secrets.
4. In a separate apply job, re-fetch PR state and exact SHA, validate changed file paths against the allowlist, apply the patch, and use an independently scoped credential to push the new PR commit. New push invokes the regular CI gates.
5. No production release or automatic merge is allowed; the existing reviewed main release process remains the only trusted publication path.

**Before activation**, configure two repository Actions secrets:
- `OPENAI_API_KEY`: key for the official `openai/codex-action@v1` runner. Use an appropriately funded/scoped key; ChatGPT Plus login alone does not provide this secret.
- `CLOSEPAW_AGENT_PUSH_TOKEN`: fine-grained GitHub PAT or GitHub App installation token with **Contents: read/write** and PR branch push permission in `al-hub/closepaw`. It **must not** be the default `GITHUB_TOKEN` because a GitHub Actions commit using that token typically will not trigger the subsequent CI workflows. Scope/expire/rotate it appropriately. Prefer a short-lived GitHub App token if a token broker is available.

The secrets must never be printed or placed in committed files. **Do not merge and enable until the runner/security review and secret provisioning are complete.** The mere presence of the workflow is not evidence of successful Codex execution. The worker has no automatic physical Samsung access; #126/#140/#142 physical gates remain unverified until explicitly run and recorded.

See <https://learn.chatgpt.com/docs/github-action> for the official action reference.
