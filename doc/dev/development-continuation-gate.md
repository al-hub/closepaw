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

## API 키 없는 운영 방식 (현재 선택)
- 추가 유료 Codex/OpenAI API 키 또는 개인 토큰을 요구하지 않는다. Codex continuation workflow는 제거했다.
- GitHub Actions는 PR CI 완료 시 해당 SHA의 두 테스트 성공을 확인하고 PR #143에 중복 없는 인계 댓글을 남긴다.
- ChatGPT 예약 작업은 주기적으로 최신 상태를 확인하고 연결된 GitHub 도구로 허용된 개발을 이어갈 수 있으나, 이는 무중단·무제한 코딩 런너가 아니며 매 실행별 한계가 있다.
- 모든 이슈 완료·실측·리뷰 후 일반 PR 병합 → 기존 main Security CI → Signed Release가 APK 서명·공개 검증을 수행한다.
- 미완료 상태에서 자동 병합/릴리스하지 않는다. 실기기 설치 및 물리 수용은 별도 결과로 표시한다.
