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

## 개발·확인 대기 최소화

- 변경을 모아 자체 검토와 필요한 저비용 확인을 끝낸 뒤 한 번에 제출한다. 실패 수정 외의 작은 보완을 CI 도중 반복 push하지 않는다.
- 일반 PR은 Python/JVM 테스트·lint·debug·기존 UI 검증을 유지한다. optimized unsigned release/R8는 main에서만 실행하고, 같은 main CI 성공 뒤 기존 키의 서명·공개 APK 독립 검증을 유지한다.
- 이미 읽은 파일·도구 스키마·성공 결과를 재사용한다. 필요한 경로/구간과 완료 job의 관련 로그만 읽고 전체 도구 목록·전체 로그를 반복 출력하지 않는다.
- CI 상태는 예상 단계 종료 시점에 맞춰 확인한다. 이벤트 알림이 있으면 우선 사용하고, 없으면 초기 2분 후 약 2분 간격을 기본으로 하되 종료가 가까운 짧은 서명 단계만 30~60초로 줄인다. 대기용 tool call을 60초 넘게 차단하지 않는다. 변화 없는 상세 조회와 같은 내용의 진행 보고를 반복하지 않고, 실패·정체·새 결과가 있을 때 상세 조회한다.
- 설치는 한 번 제출한다. installing 확인 뒤 약 60초의 재연결 여유를 두며 그 동안 다른 기록 작업을 할 수 있다. 이후 self_update.status → installed/current 확인 → 독립 get_status 순서로 확인한다. timeout이면 다음 확인까지 간격을 두고 동일 설치를 재제출하지 않는다.
- 소스·워크플로 수정만 요청받으면 그 범위에서 마친다. main-only R8 조건(4aa6a4a)의 검증은 이후 사용자가 승인한 #135/v0.1.76에서 완료했다: PR unsigned-release skipped, main R8·서명·공개 APK 검증 및 휴대폰 installed/독립 버전 확인 성공. 다음 작업에서 이 검증을 이유 없이 반복하지 않는다. 새 변경의 필요한 CI·발행은 계속 유지한다.
- 최신 #135 단일 표본은 main→공개9분53초, 최종 PR→공개18분6초, PR→휴대폰 독립 확인 약21분6초다. native cache 최초 생성 비용을 포함하며 changed-code warm 전체 릴리스는 미측정이다. 코드 작성·수정·queue/설치 승인 변동을 제외한 참고값이고 보장 시간으로 사용하지 않는다. 상세 비교·보류안은 [보고서](doc/dev/issue135-main-build-optimization.md)를 읽는다.
