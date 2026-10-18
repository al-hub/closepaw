# Gemini 실제 UI 왕복 복구 — v0.1.50 / code 51 RELEASED

최신 체크포인트: 2026-10-18 KST. [PR #95](https://github.com/al-hub/closepaw/pull/95),
브랜치 `fix/gemini-visible-controls-v0150`. **전체 CI·서명 APK 발행·배포 파일 검증 완료. 실기기 업데이트 및 자동 왕복 인수는 대기 중이다.**
이 문서가 v0.1.49 발행 당시의 설치 대기 기록보다 최신 상태다.

## 실제 확인과 남은 문제

| 항목 | 확인 결과 | 판정 경계 |
| --- | --- | --- |
| v0.1.49/code50 업데이트 | 설치 확인 | 업데이트 설치를 왕복 성공으로 보지 않음 |
| 자동 `gemini.question.ask` | `send_unavailable`, `sent=false` | 질문이 자동 전송됐다는 증거 없음; 자동 재전송하지 않음 |
| 사용자 수동 전송 이후 응답 READ | 기존 `read_app`으로 실제 응답과 완료 툴바를 읽음 | 응답 READ 성공이며 자동 전송·자동 수집 왕복 성공은 아님 |
| 같은 Voice의 자동 왕복 | 미완료 | 질문 1회 전송 → 이번 새 응답 수집 → 근거 있는 결과 설명을 검증해야 함 |

실제 질문·응답 원문, 위치·주소·좌표 및 개인 연결 정보는 이 문서나 공개 진단에 기록하지 않는다.

v0.1.49는 전송 역할을 resource ID/설명에 한정하고 클릭 플래그 조합을 엄격히 요구했다.
실제 읽기에서 전송·응답 관련 의미 정보가 보이더라도 해당 접근성 표현을 처리하지 못하는
경로가 있었다. 실제 기기의 모든 플래그·ID를 캡처한 것으로 주장하지 않는다.
대표 fixture는 확인된 의미 역할을 재현하며 실제 노드 속성의 대체 증거가 아니다.

## TDD 실패 재현 증거

[RED Security CI 39079668411](https://github.com/al-hub/closepaw/actions/runs/39079668411),
job `117533867723`: Android 테스트 **2,347개 중 3개 실패**로 이전 동작의 제한을 확인했다.

- `visibleTextSendInGoogleHostedGeminiIsRecognizedAndClicked`
- `semanticClickableControlDoesNotRequireDuplicatedClickActionFlag`
- `semanticLabelChildResolvesOnlyItsNearestClickableParent`

이는 수정 전 실패 재현이다. 후보 코드의 전체 CI 성공이나 서명 APK 발행 증거로 사용하지 않는다.

## 배포 수정 범위

- **전송 컨트롤:** text/description의 정확한 의미 역할과 기존 ID를 함께 처리한다.
  의미 라벨이 비클릭 자식에 있으면 가까운 실행 가능한 부모만 제한적으로 선택한다.
  모호한 컨트롤·입력창 포함 래퍼·일반 검색 버튼·큰 화면 컨테이너는 거부한다.
- **렌더링 대기:** 입력 검증 후 전송 버튼이 준비될 때까지 제한된 읽기만 수행한다.
  대기 중 잠금·권한·대상·입력·탐색 완전성을 재검증하며 실제 전송은 한 번만 시도한다.
- **응답 수집:** 정확히 제출된 질문 뒤의 영역을 확인한다. ID 없는 UI는 서로 다른
  좋아요·싫어요·복사 역할로 응답 툴바를 검증한 뒤 그 앞의 본문만 수집한다.
  본문은 nonblank text를 우선하고 없을 때만 description을 사용한다.
  입력창 뒤의 모델·내비게이션 표시는 응답에 넣지 않는다. 기존 명시적 ID 경로도 유지한다.
- **이전 답변 방지:** 전송 전 보이는 비편집 텍스트의 메모리 내 지문과 실제 선택한
  응답 조각 지문을 대조한다. 기준 화면에 이미 있던 문단이 섞이면 새 응답으로 반환하지 않는다. 정상 답변의 반복 문단도 보수적으로 거부될 수 있다.
  질문 원문을 응답 ID에 넣지 않는다. 완료 툴바/생성 상태와 안정성 확인을 함께 사용하고,
  길이·탐색 제한은 부분 결과로 반환한다.
- **읽기 전용 메타데이터:** 지원되지 않는 UI는 제한된 역할·개수·속성 정보로 구분한다.
  질문/응답 원문·스크린샷·토큰을 공개 진단에 추가하지 않고 재전송 없이 원인을 확인한다.

ID 없는 일반 UI에서는 좋아요·싫어요·복사 툴바가 확인되기 전의 생성 중 본문을 수집하지 않는다.
따라서 툴바가 나오기 전 시간 초과는 부분 본문을 보장하지 않으며 실패/응답 미확인으로 반환될 수 있다.
이 제한을 이전 답변이나 화면 전체 텍스트로 보충하지 않는다.

기존 MCP operation envelope, 페어링·터널·서명 키·저장된 권한을 유지한다.
초안 입력은 계속 전송하지 않는다. 중단은 관측 중단이며 Gemini 생성 취소를 뜻하지 않는다.

## 발행 및 검증 결과

- PR #95 병합. 앱 소스 및 불변 태그 `v0.1.50`: `94db85162bb3ebca5bbbe7c9238a221ff58e8609`.
- [PR Security CI](https://github.com/al-hub/closepaw/actions/runs/39080398077),
  [main Security CI](https://github.com/al-hub/closepaw/actions/runs/39080761105),
  [보안 터널 서명 probe](https://github.com/al-hub/closepaw/actions/runs/39080398083),
  [Signed Release](https://github.com/al-hub/closepaw/actions/runs/39081073211) 모두 성공.
- XML 보고서: Android **2,430개**, 실패/오류/스킵 0개. v0.1.49 대비 **86개 추가**.
  세 RED 테스트와 실제 Android 어댑터→operation→collector 통합 테스트 통과.
  브리지 테스트 55 + 8개 통과. 로컬 Gradle 및 pytest 실행 환경은 준비되지 않아 CI를 검증 근거로 사용했다.
- [공개 APK](https://github.com/al-hub/closepaw/releases/download/v0.1.50/closepaw-aa-bridge.apk) 및
  [체크섬](https://github.com/al-hub/closepaw/releases/download/v0.1.50/closepaw-aa-bridge.apk.sha256) 발행.
  공개 파일을 직접 내려받아 체크섬, manifest `ai.closepaw` / `0.1.50` / code `51`,
  OpenAI tunnel/cloudflared arm64 런타임 포함, 태그의 앱 소스 일치를 확인했다.
- APK **116,349,561 bytes**, SHA-256 `04aee12642d101437ea047e8b8cd84a072308ea5b7d0b2f56a0670274360af91`.
- 기존 서명 인증서 SHA-256
  `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba` 유지.
  CI의 apksigner 검증 및 공개 APK 인증서 대조 통과.
- 이 발행 기록 이후 문서 전용 커밋은 APK/태그 소스를 바꾸지 않는다.

## 남은 실기기 완료 조건

1. 기존 앱 위에 업데이트 설치하고 필요한 경우에만 기존 미전송 초안을 사용자가 정리한다.
2. 잠금 해제 상태에서 같은 요청 ID로 한 번 전송하고 같은 task만 조회한다.
   실제 새 답변·완료/부분 상태와 같은 Voice의 결과 설명을 확인한다.
3. 실패하면 읽기 전용 역할 메타데이터로 원인을 구분한다. 불확실한 전송을 다시 실행하지 않는다.

현재 **v0.1.50 발행 및 파일 검증은 완료, 실기기 설치/자동 왕복은 PENDING**이다.
실제 응답 READ 성공을 보존하며 P2와 자동 왕복 완료는 인수 전 ACTIVE로 유지한다.
