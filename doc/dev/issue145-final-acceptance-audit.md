# #145 최종 수용 감사 — 2026-10-25

## 판정

**#145 원래 필수 범위의 구현·혼합 실기기/자동 회귀 수용은 충족됐다. 종료 가능.**
앱은 검증·설치된 v0.1.85/code86, APK/tag source
`8ed27ad6bb9cc97a6d28a21d8a560ed2ab150088`을 그대로 사용한다.
새 실행 코드·빌드·서명·설치·질문·휴대폰 제어는 하지 않았다.

이번 작업은 새 시험 PASS가 아니라 기존 증거를 원래 수용 조건에 매핑한 감사다.
원문 완료 기준은 “구현 및 실기기/자동 회귀 통과 또는 플랫폼 제한과 안전한 대안의 확인”이다.
모든 상태/기기를 Galaxy 사진으로 다시 확인하라는 조건은 원문에 없다.
이전 후속 기록에서 추가 필수 조건처럼 취급한 Galaxy expanded-view 별도 관찰을
원래 필수 범위와 구분한다. 그 관찰 자체를 PASS로 바꾸지는 않는다.

## 항목별 증거 매핑

| 원래 요구 | 실제 증거와 범위 | 감사 판정 |
|---|---|---|
| 잠금 해제 직후 X/아일랜드 복원 지연 | PR154의 최대1초 backoff 제거/16ms·2초 bounded restore; ControlUiRestoreTest의 late keyguard, bounded timeout, stale/ended trigger 거부. Galaxy v0.1.81에서 화면 OFF→잠금 해제 후 사용자 “바로 떠” 단회 관찰 | 구현·자동 회귀 + 원버전 물리 PASS 재사용. 정량 first-visible ms는 미측정 |
| 서비스/화면 OFF 단회 복원, 중복 방지, 종료 상태 보호 | ControlOverlayWindowTest의 실제 AgentService host 재생성·이전 root detach·회전 clamp·단일 X/island arbitration·USER_PRESENT 복원·ended 재시작 거부. IslandOperationTest의 실제 emulator 비대화면(sleep/wakeup), 같은 session/epoch, OFF 중 숨김 및 복원 검사. Galaxy 접근성 OFF/ON 후 복구·위치 동일 증거 | 기존/현재 자동 회귀 및 원버전 기기 증거 재사용. OEM별 AOD 표시 사진은 별도 NOT_TESTED |
| 목표/대상/완료/현재/다음/대기·실패 이유 | sequenceProgress의 실제 수치 필드와 epoch/ACTIVE guard, GenericControlSequence 연결 경로. geminiProgress 10개 stage 전수, 다음 계획/실제 완료 구분, 실패·중단 no-replay/생성 취소 미확인, dedup/unsupported/stale 이벤트 거부 회귀 | 구현·상태/안전 회귀 충족. 계산기 기능의 기존 Galaxy PASS와 같은 상세 renderer 연결을 합성 검토. 계산기별 새 Galaxy expanded 캡처 PASS 주장은 하지 않음 |
| 실제 상세 UI 표시 | PR157의 실제 emulator AgentService/WindowManager Compose island에 short tap → expanded; waiting/completed/failed/interrupted의 실제 display crop4장. 목표/대상/완료/현재/다음/상태 및 취소 미확인/재전송 금지 문구를 직접 시각 검토. 전체 PNG8, UI11 실패/error/skip0 | 공통 renderer의 실제 window 자동 렌더 PASS 재사용. Galaxy/OEM 물리 렌더로 일반화하지 않음 |
| 기본 상태 바/단일 소유자/보안·접근성 | RemoteStatusLabel/ControlSession 라벨·중복 억제/late epoch 회귀, real overlay 소유자 교체·중복 decoration 제거·종료 cleanup. AgentService는 잠금/비대화면에 상세정보를 전달하지 않으며 controller는 keyguard 또는 !isInteractive일 때 전체 hide. 기존 잠금 인증 입력 방해 없음·X 단일 소유자 기기 관찰 | 코드 대조 + 자동/역사 기기 회귀로 수용. 새로운 PIN/인증 입력 시험을 만들지 않음 |
| 연관 inspection 계약 결함 | PR159가 정확한 session_id 전달·명시적 ACTIVE matching admission을 구현. v85 actual inspect에서 wrong_package 안전거부 후 Gemini foreground recognized_hosted_composer/foreground=true/traversal_complete=true 성공, 정상 종료 ended/epoch4 | 수정 후 별도 Galaxy 신규 PASS 확보. 질문 collector 전체 신뢰성의 증거로 확대하지 않음 |
| 배포·출처·통합 회귀 | PR159/main Security/UI/Signed 성공, 독립 raw XML2753 JVM/11 UI failure/error/skip0,8 PNG; 기존키·v3 signed-data/content digest·manifest·두 runtime·tag/source·단일설치/독립연결 검증 | 기존 검증 증거 재사용. 이번 감사에서 재발행/재설치 불필요 |

## 원시 근거

- [Galaxy unlock 사용자 단회 PASS](https://github.com/al-hub/closepaw/issues/145#issuecomment-6382726940)
- [후속 복원/서비스 재생성·회전 증거 매핑](https://github.com/al-hub/closepaw/issues/145#issuecomment-6382801949)
- [UI 구조의 실제 결함/잔여 분석](https://github.com/al-hub/closepaw/issues/145#issuecomment-6382820842)
- [PR155 상세 진행 필드 구현/회귀](https://github.com/al-hub/closepaw/pull/155)
- [실제 expanded renderer 보고서·artifact digest](issue145-expanded-island-qa.md):
  PR157/main `e6b19eedd3fee6a5ea68e1e656352d6564b0eb6f`,
  main UI40100977086 artifact12562747930
  SHA256a30580f0ac95096301a92080c8e9d4ddb560878886b034a424c0b992cfa00953.
- [v85 정확한 source·CI·서명·설치 보고서](issue145-inspection-session-v0185.md),
  [구조화 증거](evidence/issue145-inspection-session-v0185-release.json):
  main Security40144294423/UI40144294444/Signed40144807816.
- [v85 actual inspection 신규 PASS](https://github.com/al-hub/closepaw/issues/145#issuecomment-6391698102):
  start8fe91320-a81d-45c2-aecd-924499d7f66f,
  end6582776d-d1ef-490b-a6f6-ce4ea0da5364, 독립 ended/epoch4.

## 원래 제외 범위와 미검증을 보존

- 사용자 지시대로 알림/잠금화면 알림 종료·TalkBack·장시간 반복/재부팅 내구성은 #117,
  다른 기기/폴더블 이식성은 #76이다. 이를 새 PASS로 만들거나 본 이슈 종료를 막는
  조건으로 다시 가져오지 않는다.
- Galaxy의 별도 expanded-view 사진, OEM별 AOD 시각 특성, 정량 first-visible ms는
  NOT_TESTED다. 비대화면/잠금 보호와 기본 복원은 위 코드·emulator 실제 sleep/wakeup·
  Galaxy OFF/unlock 증거 조합으로 수용했으며, 이 특별 조합들을 물리 PASS로 꾸미지 않는다.
- 과거 code83/code84 질문의 reply_timeout/target_changed/outcome_unknown은 그대로 남는다.
  이는 응답 수집/실행 경로 문제이고, 현재 진행 UI가 그 상태를 거짓 완료로 표시했다는
  증거는 아니다. #145 UI 종료 조건에 새로 무한 추가하지 않는다.
  관련 task·진단과 원인 미확정 사실은 #147의 E2E 결과 관찰/안전 진단 후속에서 참조한다.
  inspection admission 결함을 고쳤다고 이 과거 실패의 실제 원인을 확정하지 않는다.
- #147의 실제 안전 최적화·matched 성능·Voice E2E는 여전히 미완료다.
  #145 종료는 #147 종료/성능 개선 판정과 무관하다.

## 변경/반복 최소화

이번 감사에서 신규 기기 시험/세션/질문/잠금·X·회전·드래그 재시험/설치/코드 변경0.
기존 PASS는 원 source/version의 REUSED_EVIDENCE다. 새 APK PASS로 무조건 승격하지
않으며, 이전 FAIL/unknown/NOT_TESTED 기록도 지우지 않는다.
추가 수동 사진은 정상 사용 중 필요할 때 확보할 보강 자료이며, 위 원래 수용을
충족한 #145를 계속 OPEN으로 두기 위한 별도 재시험의 근거로 사용하지 않는다.
