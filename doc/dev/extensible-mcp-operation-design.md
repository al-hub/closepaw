# ClosePaw 확장 가능한 MCP 기능 구조 설계안

작성: 2026-10-13 / 검토 기준: main `8e3d98b2` (v0.1.40 준비 커밋)

상태: 최초 설계 기록입니다. 후속 v0.1.41 구현과 업데이트·실기기 검증 절차는 `extensible-mcp-operations-acceptance.md`를 참조합니다. 최초 작성 시에는 구현·배포·실기기 검증 전이었습니다.

## 1. 목표와 보장 범위

새 앱, 새 조회, 새 실행, 아직 예상하지 못한 기능을 ClosePaw 내부에 등록해 확장한다. 동일한 외부 규격과 권한 범위 안에서는 MCP 도구 목록과 플러그인 스키마를 바꾸지 않는다. 기능 탐색, 입력 검증, 실행, 결과 조회가 같은 등록 정보를 사용한다.

이는 모든 미래 기능을 자동으로 구현한다는 뜻이 아니다. 새 기능의 실제 처리기, 앱 정책, 필요 권한, 테스트는 추가해야 한다. 새로운 인증 범위나 기존 계약으로 표현할 수 없는 입출력·상호작용, 호환성을 깨는 변경은 외부 규격 또는 연결 갱신이 필요할 수 있다. 최초 공통 계약 적용에는 플러그인 갱신이 필요하다.

## 2. 현재 코드에서 확인된 기반과 빈틈

| 기반 | 재사용할 내용 | 빈틈 |
|---|---|---|
| `chatgpt/McpJsonRpcHandler.kt` | MCP 입구, 상태/읽기/진단 응답 | 도구별 직접 분기; 일반 실행 없음; 요청한 MCP 버전을 그대로 반환; 스키마의 모든 제약을 런타임에서 검증하지 않음 |
| `chatgpt/ReadAppTargets.kt`, `ClosePawStatusTool.kt` | 앱 식별자를 일반 문자열로 받고 동일한 앱 목록으로 발견·읽기 연결 | 등록된 읽기 앱 목록만 제공; 설치·권한·기능별 규칙·실행 준비 상태는 별도 필요 |
| `bridge/CapabilityExecutionGateway.kt`, `ExecutionAdapterRegistry.kt` | 실행 경로별 준비 상태 검사와 공급자 선택 | 입력이 명령 문자열 중심이며 내부 capability가 enum; 새로운 구조화 작업은 상위 계층 필요 |
| `tool/ToolRouter.kt`, `PolicyEngine.kt` | 입력 검증, 정책, 승인 대기, 실행 상태 흐름 | MCP 공통 실행과 연결 필요; 화면 변경 여부만으로 외부 셸 명령의 효과를 분류해서는 안 됨 |
| `bridge/TermuxLocalBridgeExecutionAdapter.kt`, `TermuxRunCommandExecutionAdapter.kt` | 기존 터목스 실행 및 출력·오류·종료 코드 반환 | MCP 미연결; 전송 연결과 분리된 지속 작업 API 없음 |
| `tools/chatgpt-bridge/closepaw_bridge_core` | device/capability/action/input, session/command 상관관계 | Python 프로토타입이며 Android MCP와 통합되지 않음; 동기 실행, 메모리 기반 중복 거절 |

현재 채팅의 연결된 `read_app` 스키마는 두 브라우저만 허용하지만 최신 서버 코드는 일반 문자열이다. 이는 불일치 증거이며 캐시가 언제 고정되는지에 대한 증거는 아니다. 최신 APK 적용 여부 조회는 연결 경로의 HTTP 429로 실패했다.

## 3. 계층 구조

1. **MCP Facade:** 일정한 소수의 도구와 외부 envelope. 앱이나 기능마다 도구를 추가하지 않는다.
2. **Operation Registry:** 기능 descriptor, 버전, 입력/결과 스키마, 효과, 준비 검사, 처리기 연결을 소유한다.
3. **Operation Dispatcher:** 대상 해석 → 스키마 확인 → 입력 검증 → 정책 평가 → 작업 생성 → 처리기 호출 → 결과 정규화.
4. **Task Store / Runner:** 짧은 응답과 긴 작업을 같은 작업 기록으로 관리한다. 승인 대기, 재조회, 중복 요청, 중단 불확실성을 기록한다.
5. **Operation Handlers / Adapters:** 기존 앱 읽기, Linux shell 실행, 향후 앱 동작·자료 조회를 구현한다. 기존 실행 Gateway는 shell 처리기 아래에서 재사용한다.

앱 등록부와 실행 경로 등록부는 역할이 다르다. 공통 operation 등록부가 두 기반을 연결하되 기존 어댑터를 모두 하나로 다시 작성하지 않는다. Python core의 명명과 상관관계 개념은 이어받되 Python 서버를 Android 실행의 필수 중계로 추가하지 않는다.

## 4. 최초 외부 계약

아래 명칭은 제안이며 구현 시 계약 테스트로 고정한다. 기존 `get_status`, `read_app`, `get_diagnostics`는 호환 래퍼로 유지한다.

| 도구 | 고정 입력 개념 | 역할 |
|---|---|---|
| `discover_operations` | 선택적 검색어·대상·커서·operation ID | 기능 검색, 상세 입력/결과 규칙, 실제 준비 상태 조회 |
| `query` | operation ID·버전·스키마 해시·target·input·request ID | 데이터 읽기 목적의 등록된 기능 호출 |
| `execute` | query와 같은 envelope | 셸 실행, 입력, 전송 등 상태 변경 가능 기능 호출 |
| `get_task` | task ID·결과 커서·출력 제한 | 상태, 오류, 증거, 결과와 다음 페이지 조회 |
| `cancel_task` | task ID·request ID | 지원되는 작업의 취소 요청과 실제 취소 상태 확인 |

operation ID, app ID, capability ID를 외부 enum으로 제한하지 않는다. input은 JSON object로 받고 서버가 해당 operation의 세부 스키마로 검증한다. 확장용 객체는 데이터이며 권한을 높이는 명령이 아니다. query는 승인 우회 통로가 아니다. 화면 읽기에 앱을 앞으로 가져오는 부수 효과가 있으면 descriptor에 명시하고 대상 앱 정책을 적용한다. arbitrary shell은 내용이 `pwd`처럼 보여도 기본적으로 execute 경로에 둔다.

동적 발견 결과는 한 번에 전 기능을 반환하지 않고 검색·페이지·요약 → 선택한 기능 상세의 두 단계로 제공한다. 세부 스키마는 모델이 읽는 데이터이고 실행 도구의 최상위 스키마를 교체하는 방식이 아니다.

처음부터 지원하는 JSON Schema 부분집합을 명시한다. 실제 연결이 자유로운 객체형 input을 보존하지 못한다면 최초 계약 확정 전에 고정 JSON 문자열 payload로 대안을 시험하고 서버에서 파싱·검증한다. 기능 추가 후마다 이 표현 방식을 바꾸는 것은 목표에 어긋난다.

## 5. Operation Descriptor

각 등록 항목은 다음 정보를 가진다.

- `id`: 예: `app.screen.read`, `linux.shell.exec`, 향후 `app.message.send`. 이름은 예시이며 지원 선언은 실제 처리기가 있어야 가능하다.
- `version`, `schema_hash`, `input_schema`, `output_schema`, 설명과 제한된 사용 예.
- 지원 target/device/app 조건과 `read_only` / `may_write` 등 효과 분류. 화면 전환·외부 전송·파일 변경·인증 필요 여부도 별도로 기록.
- `readiness`: supported와 ready를 분리. installed, permission_missing, pairing_required, service_unreachable, disabled, ready 및 검사 시각·오류 원인.
- timeout, 최대 입력/출력, 취소 지원, 이어받기 지원, 재시도 정책, 결과 보존 기간.
- 허용된 처리기 참조. 발견 문서만 등록하고 임의 코드를 실행시키는 방식은 사용하지 않음.

발견 시의 ready는 실행 성공 보장이 아니다. 실행 직전에 대상·권한·준비 상태를 다시 확인한다. 신기능 등록에 필수인 메타데이터가 빠지면 빌드 또는 시작 시 검증에서 거절한다. 알 수 없는 기능·금지 대상·잘못된 입력은 실행 전에 구조화 오류로 반환한다.

## 6. 계약·버전·불일치

MCP 프로토콜 버전, ClosePaw 외부 envelope 버전, operation 버전/해시, APK 버전, 기능 목록 revision을 구분한다. MCP initialize는 지원 버전으로 협상하며 요청 버전을 무조건 반사하지 않는다.

외부 envelope의 추가적·호환 변경은 기존 클라이언트를 유지한다. operation의 비호환 입력 변경은 새 버전으로 등록하고 가능한 동안 기존 버전을 유지한다. 호출에 포함된 버전/해시가 달라지면 부수 효과 전에 `schema_mismatch`를 반환하고 새 상세 규칙을 조회하도록 안내한다. 새로운 기능은 동일한 외부 도구 아래에서 발견되므로 도구 목록 갱신 알림 지원에 의존하지 않는다.

실제 클라이언트가 임의 JSON object 입력과 발견→호출 흐름을 지원하는지는 초기 검증 필수 항목이다. 표준의 변경 알림 지원 여부와 이 연결에서 반영되는 동작을 혼동하지 않는다.

## 7. 작업과 결과

호출을 수락할 때 task ID를 부여하고 request ID와 입력 fingerprint를 저장한다. 짧은 작업도 같은 결과 envelope를 사용하며 이미 완료됐다면 즉시 결과를 반환한다. 긴 작업은 queued/running 상태를 먼저 반환하고 get_task로 결과를 받는다.

같은 사용자/연결 범위의 같은 request ID와 같은 내용은 기존 task를 반환한다. 같은 ID에 다른 내용은 conflict로 거절한다. 중복 요청 방지는 외부 부수 효과의 exactly-once 실행 보장과 다르다. 실행 후 기록 전 종료 등 결과 불명 상황은 `outcome_unknown`으로 기록하고 자동 재실행하지 않는다.

결과 envelope: contract version, operation ID/version, request ID, task ID, trace ID, 상태, summary, data, artifacts, error(code/message/retryable), cursor/truncated, 생성·갱신 시각. 셸 결과에는 adapter ID, stdout, stderr, exit code, timed_out을 보존한다. 조회 가능한 결과는 소유자 범위로 격리하고 크기·보존 기간을 제한한다.

취소는 요청과 확인을 구분한다. cancel_requested를 즉시 cancelled로 간주하지 않는다. 이미 끝난 작업은 기존 최종 상태를 반환한다. 처리기가 중단을 확인했을 때만 cancelled로 종료한다.

현재 RUN_COMMAND의 취소는 수신기 해제일 수 있어 프로세스 종료 보장이 없다. 이 경로는 중단을 확인할 수 없으면 unsupported 또는 unknown을 반환해야 한다. daemon의 출력 truncation/ref/duration 메타데이터를 Kotlin 결과가 축소해 버리는 부분도 보강한다. artifact 조회에는 원시 장치 경로 대신 소유자 검증이 가능한 불투명 식별자를 사용한다. HTTP 오류와 셸 종료 코드를 분리한다.

## 8. 터목스 연결 단절과 지속 실행

현재 Python daemon의 `/v1/exec`는 연결 단절 시 실행 프로세스를 종료하며 실행 시간은 최대 120초이다. Android에 task ID만 붙여도 재연결 후 계속 실행되는 기능은 만들어지지 않는다.

초기에는 기존 동기 실행의 제한과 연결 단절 결과를 정확히 노출한다. 재연결을 견디는 셸 작업이 요구되면 daemon에도 submit/status/result/cancel과 작업 기록을 추가하고 실행 프로세스 수명을 HTTP 연결에서 분리한다. APK 쪽 task와 daemon job을 추적 가능하게 연결한다. 이때도 Android/Termux 프로세스 종료나 휴대폰 재부팅 후 실행을 자동 복구한다고 보장하지 않는다. 재시작 후 결과를 조정하고 interrupted/outcome_unknown을 반환한다.

## 9. 정책과 진단

실행 전 operation 효과와 실제 target에 정책을 적용한다. 기존 승인 UI/사용자 설정을 가능한 만큼 재사용하되 기존 화면 변경 분류만으로 셸을 자동 허용하지 않는다. 클라이언트가 보낸 read_only나 approval 값이 서버 정책을 낮출 수 없게 한다. 기능 발견과 권한 범위도 일치시킨다.

터널 연결 자체를 임의 셸 실행의 권한 증거로 간주하지 않는다. 실제 터널 신뢰 경계와 호출자 식별 전달 방식을 확인하고 ingress부터 기능·작업·artifact까지 인증과 권한을 적용한다. 내부 ToolRegistry의 모든 도구를 외부로 자동 공개하지 않는다. 로컬의 제한된 작업 큐와 영속 저장소를 사용하고, 화면 입력·앱 전환은 같은 장치 자원에서 직렬화한다. 작업 기록·중복 검사·daemon 작업 ID의 연계를 원자적으로 처리한다.

발견된 operation 문서가 ChatGPT의 개별 승인 도구 정의를 자동 생성하는 것은 아니다. 공통 execute에는 상태 변경 가능 분류를 유지하고 서버가 등록된 동작과 대상에 맞는 정책을 집행한다. 새로운 권한/승인 유형은 별도 연결 변경을 요구할 수 있다.

로그는 contract/operation/schema 버전, request/task/trace ID, target, 준비 검사, 정책 결정, 선택 어댑터, 각 단계의 시작·끝·실패 원인을 기록한다. 인증 토큰과 개인 화면 내용은 기본 로그에서 제외한다. 서버 APK 버전과 실제 제공되는 MCP 계약을 진단 결과로 확인할 수 있어야 한다.

## 10. 구현 순서와 검증 기준

1. Registry/Descriptor/Dispatcher 계약과 검증을 구현한다. 기존 화면 읽기를 연결하고 호환 래퍼를 유지한다.
2. 공통 외부 도구를 최초 적용하고 연결된 ChatGPT의 실제 도구 스키마를 대조한다.
3. 터목스 shell operation을 기존 실행기에 연결한다. 정상 출력뿐 아니라 stderr, 비정상 종료, timeout, 권한/페어링 실패를 검증한다.
4. Task Store, 중복 요청, 결과 페이지, 취소/불명 상태를 구현한다. 지속 실행을 주장하려면 daemon 작업 API도 함께 검증한다.
5. **MCP 도구와 플러그인을 유지한 채** 읽기 앱 하나와 구조가 다른 시험 operation 하나를 등록하고 발견→검증→호출→실제 결과 반환을 확인한다. 이 시험이 확장성의 핵심 완료 기준이다.

필수 계약 테스트: unknown operation/extra input 거절, schema mismatch 전 효과 없음, query로 write 호출 불가, registry 발견/처리기 일치, 중복 요청 같은 task 반환, 충돌 요청 거절, 취소 경합, 연결 단절/재시작 결과, 승인 대기 상태. 기존 삼성 인터넷·크롬·Gemini 읽기는 회귀 확인한다. 실기기 증거와 단위 테스트 성공은 구분한다.

PC 다중 장치, 스케줄, 이벤트 구독, 임의 스트리밍, 분산 큐는 이번 구현의 필수 항목이 아니다. 미래 처리기로 추가할 수 있게 target/operation/artifact 구조를 열어두며 실제 요구가 있을 때 구현한다.

## 11. 아직 확인해야 하는 것

- 설치된 APK와 실제 연결된 플러그인의 계약 버전. HTTP 429는 버전 조회 실패이지 APK/Termux 결함 증거가 아니다.
- 현재 ChatGPT 연결에서 객체형 input과 최초 새 도구 목록이 수용되는지.
- Gemini 실제 대화 화면 읽기와 Voice 결과의 일치. 소스 구현 여부와 실기기 완료 여부를 구분한다.
- 새로운 scope나 외부 계약 자체 변경 시 필요한 갱신 절차. 미래의 모든 변경에 대해 무갱신을 보장하지 않는다.
