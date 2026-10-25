# #147 안전 최적화 후보 재검토 — 2026-10-25

## 결과

**원시 계측 재계산 PASS, 실제 속도 개선 NOT_TESTED, #147 OPEN.** 실행 코드·버전·APK·휴대폰 세션 변경 없음.
기존 task834ff88f-b399-4f43-81f0-a8d2b01171ce/code81을 REUSED_EVIDENCE로 사용했다.
[기계 판독 감사](evidence/issue147-candidate-audit-20261025.json)는 새 기기 측정이 아니다.

## 이번 자동 검증

- 기존 trace report 단위검사 2개 PASS: 부모 중복 합산 방지, 잘못된 clock/중복/부모 경계/형제 겹침 실패 주입.
- 원시 파일 SHA256 48eaaab23ea5b05ef96bc77da63c7736601efb2b06dd9d6afd953e6845085be6 확인.
- 139개 span 재계산 결과가 기존 stage-summary JSON 전체와 일치.
- exclusive 구간 + 미계측 구간 = 10953.098164ms(부동소수 오차 허용); 초기 probe1, 각 9단계 probe3 확인.
- 임시 파일 전달 시 추가된 마지막 LF 하나를 제거하고 원시 SHA 일치 확인 후 계산. 최초 hash 검사는 FAIL했고 원문 전송 포맷 교정 후 PASS. 원시 시간값/로그 내용 수정 없음.
- 이번 실행은 Python 감사이며 새 JVM/Android CI·기기 성능·Voice E2E 시험이 아니다.

## 같은 monotonic_process 도메인의 재사용 시간

| exclusive 구간 | ms | 해석 |
| --- | ---: | --- |
| API spacing | 6439.104063 | 최대 대기; 보안 판정 캐시 아님 |
| security_probe 잔여 | 1358.483908 | window 선택/Main 전환/API callback 등 합계; screenshot 단독 시간 아님 |
| fingerprint traversal | 888.909571 | 실제 신선한 트리 검증 |
| intent cue | 874.550662 | 다음 API spacing과 의존 관계, 삭제분을 순이득으로 계산 금지 |
| fresh fingerprint/action 잔여 | 619.902031 | traversal 제외, native performAction만의 시간이 아님 |
| post observation 잔여 | 470.359722 | security/traversal 제외 |
| 나머지 + untraced | 301.788207 | 위 합계와 총시간의 동일 도메인 잔차 |

400ms 정책은 Android elapsedRealtime 기준이다. 27×400=10800ms는 정책 산술이며 위 trace와 차감하거나 최적화 상한으로 사용하지 않는다.
Voice/AI/transport 시간은 측정되지 않았다.

## 후보별 판단

| 후보 | 판단 | 이유 |
| --- | --- | --- |
| MCP 배치 새 도입 | 신규 개선 아님 | GenericControlSequence가 이미 로컬 연속 실행 |
| cue96ms만 삭제 | 우선 적용 제외 | 다음 보안 API spacing으로 이동 가능; 표시 UX 변경 대비 실익 미입증 |
| 보안 verdict 재사용/400ms 축소 | 적용 제외 | 현재 신선성·rate-limit·보안 계약 완화 |
| cue 전 검사 제거 | 적용 제외 | 검증 전 화면 정보 표시와 보안 상태 변화에 대한 동등성 미입증 |
| post observation을 다음 pre 검사로 간주 | 적용 제외 | 시간 경과·FLAG_SECURE 전환에 대한 새 판정 계약 미충족 |
| callback/dispatch 계측 분리 | 다음 안전한 개발 후보 | 보안 동작을 유지하고 잔여1358ms의 실제 구성 확인 가능; 자체로 성능 개선은 아님 |

## 다음 구현에 필요한 구체적 계측

AndroidControlPort.probeWindow의 API 호출 직전과 callback 진입을 같은 Android elapsedRealtime 도메인으로 기록한다.
API-spacing 완료→Main 진입→실제 dispatch→callback을 분리하고 순번·시도횟수·허용된 결과 코드만 남긴다.
window ID/화면 내용/예외 메시지를 공개하지 않는다. 취소 후 callback은 buffer를 닫되 계속 실행하지 않는다.
현재 security_probe 잔여를 callback 지연으로 단정하지 않는다.

ControlTrace(System.nanoTime)의 부모/자식 모델에는 병렬 span을 삽입하지 않는다.
별도 elapsedRealtime 측정값과 기존 trace는 clock label과 시작점을 분리한다.
최초 dispatch 간격을 span EXIT 차이로 대체하지 않는다. 이 계측은 아직 구현/배포/기기 수용되지 않았다.

API 호출 빈도를 그대로 유지한 채 유의미한 개선이 가능한지 위 구성과 의존성을 먼저 입증해야 한다.
직접 dispatch 계측을 추가해도 현재28회 구조 자체의 개선은 아니다.
matched baseline/candidate 각10회 요건은 유지하고 신규 간소화 물리시험 최대3회와 구분한다.
물리 X 기존 PASS는 재사용하며 다시 요청하지 않는다. 기존 timeout/target_changed를 정상 단회로 덮지 않는다.
