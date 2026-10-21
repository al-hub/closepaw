# ClosePaw AI 개발 진입 안내

개발은 [AGENTS.md](AGENTS.md)([CLAUDE.md](CLAUDE.md))와
[현재 인수인계](doc/project/WORKS_HANDOFF.md)를 먼저 읽고 진행한다.

## 서명 APK 릴리스 — 다른 대화/Work에서도 이 경로 사용

**[GitHub Actions 서명 APK 릴리스 실행서](doc/dev/github-signed-release.md)**를 읽는다.
운영 워크플로는 [.github/workflows/signed-release.yml](.github/workflows/signed-release.yml)이다.
기존 `CLOSEPAW_*` 서명 secrets가 이미 설정되어 있어 로컬 SDK/keystore가 없어도
GitHub에서 발행할 수 있다. 새 키를 만들거나 사용자에게 키를 다시 요청하지 않는다.

일반 순서: 새 버전/code → PR 검증·병합 → main Security CI success → 자동
Signed Release → 공개 APK 검증 → 요청 범위 내 휴대폰 설치·독립 버전 확인.
dispatch 도구가 없는 경우의 예약 fallback과 수동 실행, 실패 진단, 설치 중 중복
요청 방지 절차는 실행서에 있다. 빌드 완료 전에 main 후속 커밋을 넣지 않는다.
문서만 수정할 때는 버전/APK를 바꾸지 않고 필요 시 `[skip ci]`를 사용한다.
