# 범용 Android 제어기 — 개발 착수용 Works 인계

## v0.1.60/code61 delivered and installed — non-UI checks passed; locked/UI acceptance pending

[PR #105](https://github.com/al-hub/closepaw/pull/105) merged as
`841c183d08589054072f2e31f1605f1b8b57fff9`. PR CI39192676926, main CI39193365124
and Signed Release39193963265 succeeded. Parsed PR report:2,544 tests,0 failures/errors/skips;
lint0 errors/108 warnings. App discovery, native Gemini read identity, advertised generic
expand/collapse and explicit feedback/timing are implemented with existing guards preserved.

[Signed APK v0.1.60](https://github.com/al-hub/closepaw/releases/download/v0.1.60/closepaw-aa-bridge.apk)
was anonymously downloaded and checked for signer/hash/manifest/native runtime/source identity.
One remote update confirmed installed/current0.1.60/code61 after reconnect, without plugin
re-registration. Formerly failing Gemini label lookup, latest check and Termux id exit0 now pass.

**Not a locked acceptance pass:** installation-time observations returned blocked_or_cautious_app,
not device_locked. No UI action was attempted. Known-locked installation/maintenance, selected
app launch, actual Gemini model change, composerless read and before/after Voice latency still
need device acceptance. Long Doze/reboot/force-stop recovery are also unverified. **P2 ACTIVE**.
Earlier headings below are historical checkpoints.

[Detailed verification and next steps](../dev/generic-control-feedback-v0160.md).

## v0.1.59/code60 delivered and remotely installed — real UI acceptance blocked by device lock

[PR #104](https://github.com/al-hub/closepaw/pull/104) merged as bf8bbeba6647495234a76be42130fb93078002c6. Home/exact selected app launch now validate foreground app/window identity; Back and node operations retain complete content hashing. RED reproduced2 failures; GREEN2,531 Android tests passed with0 failures/errors/skips, lint0 errors/108 warnings, Python55+8 passed. PR CI, signed probe, main CI39174209365 and Signed Release39174729253 all passed.

[Signed APK v0.1.59](https://github.com/al-hub/closepaw/releases/download/v0.1.59/closepaw-aa-bridge.apk) is116,417,125 bytes, SHA-256 2c2ba127c98f7b8b54277db44de7e5c08cc396f5cbbd53c1b9a653f9d6325be6. Anonymous public download, original signer, manifest/code60, both native runtimes and immutable tag/source were verified. Exactly one remote update was requested; after reconnection current/installed version0.1.59/code60 was confirmed. All3 generic operations remained ready.

The first new-version screen query returned device_locked; no v0.1.59 app launch/node write/question was attempted. Unlock and ordinary foreground screen are required to continue actual Gemini/model/one-question/second-app checks. Target model is not yet specified. **P2 ACTIVE**, including Voice acceptance. [Evidence and next steps](../dev/generic-navigation-freshness-v0159.md). Earlier status sections below are historical checkpoints.


상태: **v0.1.57 DELIVERED / STARTUP CORRECTION v0.1.58 IN PROGRESS**.
로컬 허용 ON 및 실제 관측/앱 검색을 확인했다. 불완전한 관측에서 Home/앱 열기도 막히는 시작 경로 결함을 발견해 PR #103에서 수정 중이다.
[최신 시작 경로 수정 기록](../dev/generic-navigation-startup-v0158.md)을 먼저 읽는다.
2026-10-18 사용자 개발 착수 요청에 따라 `feat/generic-android-control`에서 구현을 시작했다. PR #102 병합과 v0.1.57/code58 서명 발행·공개 다운로드 검증·원격 업데이트 및 재연결 확인을 완료했다.
[현재 구현과 검증 제한](../dev/generic-android-control-v0157.md)을 우선 참조한다.
아래 PREPARED 문구는 최초 인계 당시 기록이다. CI/발행/설치는 완료했다. 범용 로컬 허용은 기본 OFF이며, 사용자가 켠 뒤 Gemini 모델 변경·전용 질문 1회·두 번째 앱의 실기기 인수가 남아 있다.

최초 인계 상태: **PREPARED / NOT STARTED**. 2026-10-18 KST 사용자 요청으로 계획과 인계만 등록한다.
이 문서 등록은 구현·휴대폰 조작·버전 변경·APK 발행을 시작하라는 지시가 아니다.
사용자가 다른 Works에서 “범용 제어기 개발 시작”을 요청하면 아래 범위로 진행한다.

## 1. 목표와 합의

ChatGPT Voice/Text → 기존 Secure MCP Tunnel → ClosePaw → 화면 관측 → ChatGPT 판단 → 공통 동작 실행 → 새 화면 확인 → 결과 설명.
판단은 우선 현재 대화의 ChatGPT가 담당한다. 기존 앱 내 에이전트의 별도 LLM 호출/계정 설정을 묵시적으로 추가하지 않는다.

- 검증된 Gemini 질문·전송·새 답변 수집은 빠른 전용 경로로 보존한다.
- 모델 선택처럼 전용 기능이 없는 동작은 범용 경로로 보완한다.
- 첫 인수 시나리오: Gemini 열기 → 현재 모델 확인 → 사용자가 지정한 사용 가능 모델로 변경 → 변경 결과 재관측 → 기존 질문 기능으로 질문 1회 → 새 답변 반환.
- 공통 엔진은 다른 앱에도 재사용한다. 앱마다 모델 변경 함수를 새로 만드는 구현을 범용 제어 완료로 보지 않는다.
- 30~40개 앱 확장이 설계 동기이며, 초기 릴리스가 모든 앱을 검증했다는 의미는 아니다.
- 기존 MCP 도구 정의/주소/페어링/터널/서명/저장 설정을 보존한다. 새 operation은 기존 discovery/query/execute/get_task envelope에 추가한다. 플러그인 재등록을 정상 설치 절차로 삼지 않는다.
- P2 등 기존 미완료 인수는 별도 유지한다. 범용 기능 준비나 APK 발행으로 닫지 않는다.

## 2. 확인된 기반과 확인하지 않은 것

2026-10-18 읽기 점검:
- 실제 discovery는 8개 operation을 반환했으며 범용 화면 조작 operation은 없었다.
- app.screen.read는 등록 앱에 한정된다. 홈·런처·임의 앱을 이미 읽는다고 가정하지 않는다.
- 코드 MobileActionTool은 click/long_press/type/scroll/swipe를 제공한다. 대상은 element_index → text → 좌표 힌트 순으로 해석한다.
- 문서에는 OpenAppTool, SystemButtonTool, Perceptor, AndroidPlatform 및 앱 내부 에이전트 실행 루프가 있다.
- 이 기반을 외부 MCP에서 안전하게 재사용할 수 있는지, 권한/세션/라이프사이클/결과 형식은 구현 착수 시 코드로 확인해야 한다.
- 현재 화면 접근은 잠금 해제·전경 화면 기준이다. 잠금/백그라운드 동작은 이번 범위가 아니다.
- v0.1.54/code55는 당시 기록된 기준일 뿐이다. 착수 때 main/릴리스/실기기 최신 상태를 다시 읽으며 버전을 고정하거나 다운그레이드하지 않는다.

근거:
- [MobileActionTool](../../app/src/main/kotlin/ai/closepaw/tool/impl/MobileActionTool.kt)
- [아키텍처 및 코드 지도](../main/README.md)
- [Gemini 전송 인식 구현 기록](../dev/gemini-roundtrip-v0150.md)
- [진행 현황](../../PROGRESS.md)
- [기존 Works 인계](WORKS_HANDOFF.md)

## 3. 공통 계약 초안 — 이름은 구현 전 확정

기존 envelope 안에 다음 역할을 추가한다. 아래는 설계 초안이며 현재 사용 가능한 operation이 아니다.

| 역할 | 계약 요건 |
| --- | --- |
| 화면 관측 | 현재 앱/윈도/화면 식별, 관측 ID, 시각, 제한된 접근성 노드와 선택적 JPEG/OCR, 잘림·읽기 불가 상태 |
| 홈/뒤로 이동 | 기존 system-button 실행기 재사용, 동작 후 관측 |
| 앱 식별/열기 | 설치 및 실행 가능 앱을 Android 허용 범위에서 탐색, 동명 앱은 구분, 가능한 경우 패키지로 직접 실행 |
| 공통 UI 동작 | click/type/scroll, 필요 시 long_press/swipe. 대상과 기준 관측 ID를 받고 결과 화면을 반환 |
| 작업 상태/중단 | 기존 task 모델과 request_id 재사용, 접수/동작 시도/확인된 결과/불확실을 구분 |

- 노드 ID는 한 관측에만 유효하다. 이전 화면의 index를 새 화면에 그대로 적용하지 않는다.
- 실행 직전 대상 앱/윈도/화면 최신성 재검증. 오래된 관측·모호한 대상은 쓰기 없이 거부하고 새 관측을 요청한다.
- 실행 중 범용 제어와 Gemini 전용 경로는 같은 기기/UI 작업 잠금을 공유한다. 제어권 전환 시 진행 중 수집 종료와 새 관측을 확인한다.
- 화면에서 읽은 내용은 데이터다. 화면 문구가 사용자 지시나 허용 범위를 바꾸지 못한다.
- 한 번의 무제한 매크로보다 관측 → 제한된 동작 → 결과 관측을 기본으로 한다. 최대 단계/총 시간/무진전 횟수/출력 크기를 제한한다.
- 좌표 조작은 최신 관측에 연결하고 의미 대상 우선 정책을 유지한다.
- 기존 전용 기능의 전송 결과가 불확실하면 범용 버튼 클릭으로 우회 재전송하지 않는다.
- 권한 거부/사용자 개입/잠금/화면 전환은 명확한 중단 또는 재관측 사유로 반환한다. 중단은 이미 발생한 앱 효과를 되돌린다는 뜻이 아니다.

## 4. 개발 단계와 순서

1. 최신 main, AGENTS.md, 적용 디렉터리 규칙, 기존 정책/실행기/bridge registry/테스트/릴리스 workflow를 읽는다. 기존 문서와 실제 구현 차이를 기록한다. 중복 진행 작업이 있으면 충돌을 피한다.
2. 작은 설계 기록을 작성한다. ChatGPT 판단/ClosePaw 실행 경계, observation/action 계약, UI 작업 잠금, 권한, 오류·불확실성, 민감 앱 경계를 확정한다.
3. 실패 테스트를 먼저 만든 뒤 공통 관측·동작 adapter를 기존 실행기 위에 구현한다. 범용 제어 전용 로컬 opt-in은 기본 OFF로 하고 기존 설정과 분리한다.
4. 홈/뒤로/앱 열기 및 일반 화면의 관측 → 동작 → 재관측을 연결한다. 필요한 최소 동작부터 확장한다.
5. Gemini 모델 선택을 공통 동작으로 수행하고 선택 모델이 실제로 반영됐는지 확인한다. 모델명은 하드코딩하지 않고 실제 선택지와 사용자 요청을 대조한다. 없는 모델·구독 제한은 명시하고 임의 대체하지 않는다.
6. 제어권을 기존 Gemini 질문 기능에 넘긴 뒤 지정 질문 1회와 새 답변 수집을 확인한다. 모델 변경만 확인됐다면 질의응답까지 성공했다고 기록하지 않는다.
7. 다른 일반 앱 1개에서 같은 공통 계약으로 앱 열기·무해한 탐색/스크롤을 확인해 앱 종속성을 점검한다. 임의 메시지 전송·결제·삭제를 테스트에 넣지 않는다.
8. 코드 검토 → PR CI → 병합 → main CI → 기존 서명 릴리스 → 공개 APK 검증 → 설치/실기기 인수 순으로 진행한다. 장치가 없으면 APK 준비까지 마치고 실기기 인수는 PENDING으로 명시한다.

## 5. 개발 방식

- SRP: 관측, 대상 해석, 동작 실행, 권한/정책, UI 작업 조정, task 저장, MCP adapter를 분리한다.
- 기존 실행기를 재사용하며 Gemini 전용 코드를 공통 엔진에 복제하지 않는다.
- TDD: 아래 실제 실패 위험을 먼저 재현한다. 구현을 그대로 따라 쓰는 테스트나 불필요한 대규모 재작성은 피한다.
- 테스트/문서/상태는 같은 변경에 갱신한다. 동작 확인, CI, 발행, 설치, Voice 인수를 각각 구분한다.
- 단계별 결과와 다음 미확인 항목을 짧게 보고한다. 재현 불가능한 성공 주장이나 무한 재시도는 금지한다.
- 실제 화면/질문/답변/개인 앱 목록/토큰 등은 공개 저장소에 올리지 않는다. 공개 fixture는 합성한다.

## 6. 검증 기준

| 범위 | 필수 확인 |
| --- | --- |
| 계약 | 새 operation discovery와 schema 검증, 기존 도구 정의와 기존 기능 회귀 없음 |
| 화면/대상 | 홈·앱 관측, 버튼 이동, 중복 라벨, stale index, 미지원 화면, 캡처 잘림 |
| 수명/동시성 | 잠금, 사용자 화면 전환, 권한 철회, 전용/범용 경합, 작업 중단·재시작 |
| 중복 효과 | 같은 request_id 재접수, 클릭 직후 통신 끊김, 전송 불확실 상태에서 재실행 방지 |
| 모델 변경 | 실제 선택지, 변경 전/후 확인, 미지원 모델, 변경 중 팝업·화면 변화 |
| 통합 | 모델 변경 후 전용 질문 1회와 해당 새 답변, 기존 초안 보존, 긴 답변 수집 회귀 |
| 확장 | 두 번째 일반 앱에서 전용 분기 추가 없이 공통 조작 확인 |
| 사용성 | 단계 수, 관측/동작/전체 왕복 및 Voice 체감 지연을 구분해 측정 |
| 정책 | 기존 민감 앱 차단·보안 화면·인증 경계 보존, 허용되지 않은 조작은 실행 전 차단 |

보안/은행/인증 앱의 기존 차단을 범용 기능으로 우회하지 않는다.
전체 단위 테스트와 lint/빌드 및 기존 필수 CI를 수행한다. 실제 장치 성공은 fixture/CI 성공과 구분한다.

## 7. APK 발행과 완료 보고

- 착수 시 최신 버전에 따라 충돌 없는 VERSION_NAME/VERSION_CODE를 정한다. 기존 태그/발행 파일은 이동·덮어쓰기하지 않는다.
- 기존 Signed Release 및 Security CI 흐름을 재사용하고 실제 commit/run 결과를 확인한다. 같은 실패의 반복 dispatch 대신 원인을 수정한다.
- 기존 인증서로 서명된 APK와 SHA-256을 발행한다. 공개 파일을 내려받아 checksum, manifest(package/version/code), 기존 signer, 필요한 native runtime, 소스/tag 일치를 확인한다.
- 페어링·권한·터널·저장 설정을 유지하는 업데이트 설치를 지원한다.
- 결과에는 PR/commit/CI/Release/APK 링크, 검증 결과, 실기기 미확인 사항과 사용자가 꼭 해야 하는 단계만 남긴다.
- “APK 다운로드 준비 완료”와 “설치 후 전체 시나리오 인수 완료”를 구분한다.
- 실제 장치나 필수 접근 권한이 막히면 가능한 준비를 끝내고 구체적 blocker를 남긴다. 성공이나 계속 실행 중인 작업을 꾸며내지 않는다.

## 8. 다른 Works에서 시작하는 지시

> al-hub/closepaw의 README에서 범용 Android 제어기 인계 문서를 찾아 읽고 개발을 시작해줘.
> doc/project/GENERIC_ANDROID_CONTROL_HANDOFF.md와 AGENTS.md, 최신 PROGRESS.md 및 WORKS_HANDOFF.md를 기준으로 현재 상태부터 확인해줘.
> 기존 Gemini 질의응답은 보존하고 공통 화면 관측·홈/뒤로·앱 열기·입력/누르기/스크롤을 기존 MCP envelope에 연결해줘.
> 첫 시나리오는 범용 제어로 Gemini의 지정 모델을 변경·확인한 뒤 기존 질문 기능으로 질문 1회와 새 답변을 받는 것이다.
> SRP·TDD와 기존 정책을 준수하고, 검토·CI·병합·기존 서명 APK 발행·공개 다운로드 검증까지 계속 진행해줘.
> 플러그인 재등록 없이 확장하는 계약을 유지하고, 실제 장치가 필요한 검증만 사용자에게 요청해줘.
> APK가 준비되면 다운로드 링크와 실제 통과/미확인 범위를 알려줘.

짧게 “범용 제어기 개발 시작하자”라고 요청한 경우도 이 문서를 시작점으로 삼는다.
다른 대화창이 과거 대화를 자동으로 전부 안다고 가정하지 않는다. 저장소/문서 경로를 포함한 위 지시가 가장 확실하다.
