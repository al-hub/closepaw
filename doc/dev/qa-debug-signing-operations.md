# ClosePaw — 고정 서명 Debug QA APK 운영 지침

> 2026-11-22 #147 / Draft PR #286 작업에서 확인한 반복 사용 절차. 이 문서는 **운영 Release 서명과 별개**인 `ai.closepaw.capturecandidate` 후보 APK에만 적용한다. 최신 실제 상태는 workflow run과 실기기 확인이 우선한다.

## 1. 변하지 않는 구분

- 정식 Release: `ai.closepaw`, 기존 운영 서명키, [signed-release 절차](github-signed-release.md). 운영 키를 생성·교체하거나 Release APK를 변경하지 않는다.
- QA Debug 후보: `ai.closepaw.capturecandidate`, 오렌지색 DEBUG 식별, **별도의 장기 유지 QA 키**. 일반 Android 임시 debug keystore와 혼동하지 않는다.
- **APK 서명 호환성은 앱 이름이 아니라 package + 서명 인증서로 결정**된다. 같은 Debug 명칭만으로 기존 설치본을 덮어쓸 수 없다.
- 관련 작업: [#147](https://github.com/al-hub/closepaw/issues/147), [PR #286](https://github.com/al-hub/closepaw/pull/286), [후보별 상세 가이드](https://github.com/al-hub/closepaw/blob/test/147-orange-debug-candidate-20261122/doc/dev/issue147-fixed-debug-signing.md).

## 2. 이미 마련한 서명 자산 — 재생성 금지

2026-11-22의 실작업 기록에 따르면, **ClosePaw → 갤럭시 Termux Bridge → 저장된 노트북 SSH → WSL(`al-hub`)** 접속을 통해 `gh` 인증 및 `keytool` 사용이 확인되었고, 전용 QA 서명키와 아래 GitHub Actions 설정을 최초 등록했다. 이후 대화에서도 기존 경로를 재사용한다. 같은 포트·연결·승인 여부를 변경 증거 없이 매번 초기화하지 않는다.

- 키 저장 위치(WSL 계정 홈 기준): `~/.local/share/closepaw/qa-debug-signing/closepaw-qa-debug.p12`; alias `closepaw-qa-debug`. 이 경로는 **비밀 원본의 위치 정보**일 뿐 공유나 백업을 의미하지 않는다.
- GitHub Actions repository secrets(이름만 기록): `CLOSEPAW_QA_DEBUG_KEYSTORE_BASE64`, `CLOSEPAW_QA_DEBUG_KEYSTORE_PASSWORD`, `CLOSEPAW_QA_DEBUG_KEY_ALIAS`, `CLOSEPAW_QA_DEBUG_KEY_PASSWORD`.
- GitHub Actions repository variable(공개 인증서 지문): `CLOSEPAW_QA_DEBUG_CERT_SHA256`. **실제 기대값은 이 변수와 서명된 APK에서 읽어 비교**하며, 과거 메모의 지문을 추측해 쓰지 않는다.
- 최초 설정/복구 스크립트: [`scripts/qa-debug-signer-setup.sh` (#286 브랜치)](https://github.com/al-hub/closepaw/blob/test/147-orange-debug-candidate-20261122/scripts/qa-debug-signer-setup.sh). 이미 키가 존재하면 재생성하지 않도록 설계되어 있다. 자동 재실행 전에 현재 자산·분기·안전성을 점검한다.
- 인증 정보, keystore, password, base64, `gh` 토큰은 채팅·이슈·PR·커밋·로그·artifact에 기록하지 않는다. 별도의 보호된 오프라인 백업을 유지하고, 복구 시에도 무단 키 교체는 금지한다.
- 이전 연결 실패 보고만 믿고 SSH/WSL 경로가 불가능하다고 단정하지 않는다. **확인된 ClosePaw 경로를 우선**하되, 변경·장애가 관측될 때만 최소 범위로 진단한다.

## 3. 다음 개발에서의 가장 짧은 발행 흐름

1. `AI.md`와 이 문서 및 **해당 PR의 정확한 HEAD**를 확인한다. 이미 등록한 GitHub Secrets를 다시 만들거나 사용자에게 비밀번호를 요청하지 않는다. 미설정 오류가 *실제로* 발생하면 인증된 WSL에서 키 원본과 `gh` 설정을 점검한다.
2. 관련 코드/Android 의존성의 검증을 진행하고, **해당 HEAD의 Security CI + Premium UX UI가 성공했는지** 확인한다. 이전 HEAD 성공을 재사용하지 않는다.
3. 전용 QA 빌드 워크플로는 `.github/workflows/premium-ux-ui.yml`의 `capture-candidate` job, 빌드 스위치는 `-PclosepawCaptureCandidate=true`다. 필요한 경우 검토된 후보 브랜치에서만 `build_capture_candidate=true`로 dispatch한다. 실행 중이거나 완료된 동일 빌드를 중복 요청하지 않는다.
4. 산출물에서 패키지 `ai.closepaw.capturecandidate`, `debuggable` 여부, 버전/code, 서명 인증서 SHA-256과 고정 변수의 일치, 파일 SHA-256, 출처 commit/workflow run ID를 확인한다. 서명 누락·불일치이면 **fail closed**; 임시 키로 대체하지 않는다.
5. 설치 전에 **휴대폰의 기존 candidate 인증서를 별도 읽기 전용으로 비교**한다. 일치할 때만 이미 허용된 갤럭시 Termux/무선 ADB 경로를 통해 한 번 업데이트하고, 설치본 버전·서명·접근성·데이터 유지 여부를 확인한다. 유선 PC ADB는 사용하지 않는다.
6. 서명이 다르면 업데이트를 강행하거나 자동 제거하지 않는다. 기존 앱의 데이터/접근성/오버레이/화면공유 동의·연결 설정이 초기화될 수 있음을 먼저 알리고, **명시적 승인 후** Debug candidate만 별도 전환한다. 운영 `ai.closepaw`은 절대 제거하지 않는다.
7. 사용자가 승인한 QA 세션 안에서만 실제 제어를 실행한다. Chrome 빈 입력란 및 삼성 한글 키보드를 식별하고 `ㄱ→ㅏ→Space→ㄴ→ㅏ→Space` 입력, 독립 `가 나 `(끝 공백)·커서 4/4 확인을 기록한다. 후속 29자 `오늘 회의는 zoom으로 3시에 시작, 자료는 2개.`와 3회 측정은 **구현·실행한 경우에만** 성공으로 표시한다.
8. 세션을 종료하고 예외·불명확한 입력은 별도 표시한다. 실패 재시도 최대 3회, 중복 입력 자동 재전송 금지, 상태·로그는 #147에 commit SHA·run ID·APK hash·실기기 결과로 연결한다. #147 OPEN, #286 Draft의 종료 여부는 실제 수용 증거에 따라 결정한다.

## 4. 2026-11-22 기준 실측·미완료 경계

- **확인된 상태:** ClosePaw→Termux→SSH→WSL 접근 성공, `gh`와 `keytool` 확인, QA 전용 키 생성과 GitHub Actions secrets 4개/지문 변수 등록 보고. Release는 변경하지 않았다.
- **기존 후보와 인증서 불일치:** 2026-11-22 기준 갤럭시 기존 `ai.closepaw.capturecandidate` 설치 인증서가 새 QA 고정 인증서와 달랐다는 기록이 있다. 이 상태는 향후 설치 후 달라질 수 있으므로 **설치 직전 재확인**해야 한다.
- **아직 미확정:** 당시 [APK 발행 run 45020104528](https://github.com/al-hub/closepaw/actions/runs/45020104528), [Security run 45020104640](https://github.com/al-hub/closepaw/actions/runs/45020104640)은 진행 중이었다. 해당 시점 APK 발행 완료, 설치 성공, 실제 6타 PASS는 **확인되지 않았다**. 이후 결과는 해당 실행 링크와 새로운 이슈 기록을 기준으로 갱신한다.
- **출처:** [#147 실행 기록](https://github.com/al-hub/closepaw/issues/147#issuecomment-7078821255), [PR #286](https://github.com/al-hub/closepaw/pull/286).

## 5. 보안·운영 원칙

비밀키 등록이 **한 번 완료됐다**는 사실과, 이후 모든 CI/실기기 테스트가 성공했다는 주장은 다르다. Debug APK 발행·서명 검증·기기 설치·입력 실험은 각각 독립 단계다. 신뢰할 수 없는 PR/워크플로 변경에 QA signing secrets를 노출하지 않으며, 보호된 서명 자산 접근 범위는 코드 리뷰와 저장소 권한으로 제한한다. CI나 인증서 문제는 정확한 실패 원인부터 진단하고 보안 검사 생략, 임시 서명 대체, 운영 키 전용으로 우회하지 않는다.
