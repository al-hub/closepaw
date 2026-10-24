## 2026-10-24 v0.1.83 후속 사용자 요청 검증 — 신규 단회 PASS

- [#145 증거](https://github.com/al-hub/closepaw/issues/145#issuecomment-6385036279), [보고서](../dev/gemini-native-short-reply-v0183.md). 현재0.1.83/code84 연결 확인 후 다른 무해한 질문1회 task `2635ac82-5ff5-4322-a1b6-78699f9beb0c`: **succeeded/completed**, 기대 숫자 일치, sent=true, outcome_unknown/partial/timed_out=false, coverage=complete, pages1, paged_start_overlap_end_and_stable. 신규 PASS이며 아래 이전 FAIL/unknown 결과는 불변.
- collection_ms5788(System.nanoTime), roundtrip completed elapsed_ms9021(Android SystemClock.elapsedRealtime). 별도 clock 차감/Voice E2E·성능 개선 주장0. 실제 최초 답변 생성시각은 미측정.
- 새 세션04320b0d-f608-4c3c-b472-6ca65ff701b5, 시작59669d14-0bca-4960-b149-38fd3d0d872d, 종료620f352b-211e-4e0e-86ef-258fe2487853 success→독립 ended/epoch4. 재전송/재설치/코드변경/기존 X·잠금·회전·드래그 물리 재시험0.
- **#145 OPEN**: 상세 진행 패널 실제 렌더 NOT_TESTED, 간헐적 target_changed 원인·빈도 미확정. **#147 OPEN**: matched 성능 개선/Voice E2E 미입증. #146/#150 CLOSED 불변. 다음은 기존 실패 진단 분석과 상세 패널 표시의 최소 관찰이며, 현재 종료 세션을 임의 재개하거나 단회 성공을 반복 안정성/전체 완료로 일반화하지 않는다.

## 2026-10-24 v0.1.83 발행·설치 및 단회 Gemini 실검증

- [PR156](https://github.com/al-hub/closepaw/pull/156) 병합, APK/tag source `430f42290d2f8bdc3d10c0c451d677ae7f6f6407`. [보고서](../dev/gemini-native-short-reply-v0183.md), [검증 metadata](../dev/evidence/gemini-native-short-reply-v0183.json).
- 기존 code83 실패 메타데이터에서 non-scrollable native history와 별도 다음 toolbar row를 확인. 정확한 native 소유권 구조를 지원하며 generic parser/완료역할3개/안정성/prior text/세션/잠금/취소 보호 유지. 실제 정답 최초 생성시각은 미측정.
- 신규6 포함 JVM2743, UI10 실패/error/skip0 독립 XML 확인. Python39 PASS, PR Security40096550298/UI40096550287 및 main Security40097087564/UI40097087636/Signed40097583090 success. 로컬 Gradle unavailable는 RED assertion 아님.
- 공개 APK0.1.83/code84,116642415bytes,SHA256 `c55a6b402d6f04513f72b73bcf77cd38ae1356cc3f1fa0925a26c0e084065b15`. 익명 download+checksum, 기존 signer/v3 RSA-SHA512 signed-data·content digest, binary manifest, 두 runtime, annotated tag/source 독립 PASS.
- 설치1회 task `78bb9aa8-daec-45a7-a259-bc87482f85ba`. 교체 중 첫 status timeout 후 간격을 두고 durable installed/current0.1.83/code84→독립get_status 정상. 재설치0.
- 새 무해한 질문1회 task `9cf42b92-ae35-4c7b-bbdc-9040e550ad83`: **FAIL/수용 미완료**, outcome_unknown/target_changed, send_action_invoked=true, collection_ms80. foreground=false/target_not_verified로 조기 종료; 동일 Google package/window 정보만 남아 실제 앱전환/사용자개입/composer 일시소실 중 무엇인지는 미확정. 단회 read_app에서 새 질문과 기대 정답 확인했지만 원 task PASS 승격0/재전송0. parser 수정의 실기기 수용은 미입증.
- 진단 elapsed_ms는 Android SystemClock.elapsedRealtime, collection_ms는 System.nanoTime 기반 별도 시작점, timestamp_ms는 wall clock. 교차 clock 차감/Voice 성능 개선 주장 금지.
- 종료 task `57a506c3-7273-42fd-bf69-1d3bad235304` success, 독립 ended/epoch2(session85c9019e-13d5-4eab-a04e-1492b96032b1). 완료된 단회 시험을 근거로 새 질문/임의재개 금지. 기존 X/잠금/회전/드래그 물리 시험 반복0.
- **#145 OPEN**(expanded 상세 패널 실제 수용 NOT_TESTED 및 새 foreground 검증 실패), **#147 OPEN**(Android/Voice 개선 미입증); #146/#150 CLOSED 유지. #117/#76 분리 불변. 다음: privacy-safe root/composer/window 상태 구분 후 bounded READ-ONLY same-window settling 설계·회귀. wrong app/window/lock/revocation/cancel/user intervention fail-closed 유지; 질문 재전송으로 복구 금지.

## 2026-10-24 v0.1.82 Gemini 단회 실검증 — 자동 응답 수집 FAIL

- [#145 원시 판정](https://github.com/al-hub/closepaw/issues/145#issuecomment-6383810155). 설치본0.1.82/code83 응답 정상. 명시적 새 세션에서 무해한 짧은 질문1회 task `8ef8ab53-ba0b-4edf-8163-3b1a3323747e` 실행.
- 결과 timed_out/reply_timeout, sent=true, coverage=unconfirmed, collection_ms45239. 자동 재전송0. 이후 read_app text+JPEG에서 기대 정답이 보였지만45초 이전 생성 여부는 미확인. 후속 화면 관찰을 원 task PASS로 승격하지 않음. 지연 생성인지 자동 수집기 인식 문제인지 미확정.
- gemini.ui.inspect는 메타데이터 검사 실패(INVALID_ARGUMENT/세부 원인 없음), 재시도 없음. 기본 아일랜드 캡처만 확보했으며 완료·현재·다음 상세 패널의 실제 렌더는 NOT_TESTED. #145 OPEN, 실제 속도 개선 #147 OPEN 유지. #146/#150 CLOSED 불변.
- 검증 종료 task `292199f7-fa47-4198-8a7f-2f9368c1c913` success 및 독립 ended/epoch2 확인(세션ad6c7098-2c1f-45de-b35d-7d714bf7e6bb). 새 질문/임의 재개/기존 물리 시험 반복/코드·APK 변경 없음.
- 다음은 기존 task·진단 기반 응답 인식/수집 경로 분석과 상세 패널 관찰 수단 확보다. 원인 분석 전 동일 질문 재전송 금지. 시간값의 구현 clock domain을 이번에 독립 확인하지 않았으므로 Voice/네트워크 구성비나 교차 clock 차감 결과를 주장하지 않는다.

## 2026-10-24 PR #155 발행·설치 완료 — v0.1.82/code83

- [PR155](https://github.com/al-hub/closepaw/pull/155) 병합. main/APK/tag source `19382f9a1e7502d5db452dcd7c4ea610121826ea`. [발행 보고서](../dev/issue145-v0182-release.md), [metadata](../dev/evidence/issue145-v0182-release.json).
- Gemini 상세 진행 완료·현재·다음 표시 및 모든 stage/실패/중단/dedup/stale 회귀 추가. 속도 개선 변경은 없음.
- 첫 code83 UI1건 실패 후 로그에서 awaitCondition 이중 관찰 TOCTOU를 확인해 fixture만 보완: 같은5초 기한,50ms 간격2회 연속true. 최종 PR Security40086586708/UI40086587217, main Security40087024516/UI40087024566/Signed40087550035 success. 원시2737 JVM/10 UI 실패·skip0 및 PNG4 독립 확인.
- 공개 APK116642415bytes, SHA256 `dac1049eaa0e3284b932ed7a7f4fc270134f8971e27f43c716effd7929848086`. 기존 signer/v3 RSA-SHA512 signed data·content digest, binary manifest0.1.82/83, 두 runtime, tag/source 독립 PASS.
- 설치1회 task6c9e0c84-f093-4980-8c80-4f3983a50e53: durable installed/current0.1.82/code83 → 독립get_status 정상. UI세션 ended/epoch0(2c59d492-3f60-4f6d-a19e-7c3d6f41cb46). 재설치/새 물리시험/임의재개 없음.
- **최신 이슈 상태:** #146과#150 CLOSED. #145 OPEN(새 상세 패널 실제 완료/실패/취소 표시 수용 미확인), #147 OPEN(실제 속도 개선/Voice E2E 미입증). 아래 v0.1.81 인계 당시의 #146 OPEN/잠금 미확인 문구는 역사 기록이다.
- 이후 확보한 v0.1.81 실기기 PASS: 잠금 해제 즉시 X/아일랜드 표시(145 댓글6382726940), 회전 정상·접근성OFF/ON 전후 동일 위치·복원 좌표393,309(146 댓글6382801637). 기존 X/Y59/잠금/회전/재생성 시험 반복 금지. #117/#76 분리 유지.
- 다음 검증은 실제 Gemini 상세 패널의 미확인 표시만 필요하다. 기존 일반 기능 전체 재검증이나 같은 APK 재설치는 불필요. 이 기록은 docs-only, APK source/tag/키는 불변.

## 2026-10-24 후속 발행·설치 완료 — v0.1.81/code82

- [PR154](https://github.com/al-hub/closepaw/pull/154) merged, immutable APK/tag source`c14b8883f5a659cba851412ce2c53fa90b32d22e`. [최종 보고서](../dev/issues145-150-v0181.md),[metadata](../dev/evidence/issues145-150-v0181-release.json).
- PR Security40071331377/UI40071331372,main Security40071795852/UI40071795881/Signed40072145174 success. 독립 main XML2736 실패/skip0,PR UI10 실패/skip0·PNG4,Python39 PASS.
- APK116642415bytes,SHA256`68eec6d1aa6831f0a15cc9cce2d3f83a81005738f0af668941382b9b05e0972b`. 공개 download,기존v3 signer/RSA-SHA512 signature·content digest,binary manifest82/0.1.81,두runtime,tag/source 독립 PASS.
- 설치단1회 task664e8d7e-5d8f-4332-b28d-b0eb9530c772:durable installed/current0.1.81/code82→독립get_status 정상. UI세션 ended/epoch0(72db083c-f596-4240-a5a3-cc168e05dc80) 유지. 임의재개0,새물리시험0. 같은APK 재설치금지.
- #145 OPEN:최대1초 keyguard 감지공백→16ms/2초bounded polling,JVM PASS. 실제Galaxy first-visible/단회OFF-unlock NOT_TESTED.
- #146 OPEN:touch중hold→unknown/관찰소비/no-replay 신규PASS,기존emulator 재생성/회전/clamp PASS. 현재Galaxy 재생성·회전 물리미검증. X/Y59 기존PASS 재시험금지.
- #147 OPEN:기존raw139span local10953.098ms 중API spacing6439.104ms(58.8%) 최대.400ms보안간격/모든fresh검사 유지,개선미입증. 과거완료task2개복구→code81의3표본10952/11032/10979ms(mean10987.667) 확보;신규3회가 아님. strict10+10/Voice E2E NOT_TESTED.
- #150 자동체계 수용완료:위조signer·Boolean time RED→수정,독립historical/current XML/report/artifact digest/source/version/signer 검증. 기존PASS와현재PASS 구분. #117TalkBack/알림/장기반복,#76다른기기/폴더블 유지.
- 다음최소사용자협조:새로허가한세션에서 단회OFF/unlock 표시확인 및 필요시현재Galaxy 재생성·회전. ended무단재개금지. #147은 안전한실제최적화와client timing 경로가 더필요. 이기록은docs-only,태그/키/APK 불변.

## 2026-10-24 후속 개발 후보 v0.1.81/code82 (#145/#146/#147/#150)

- 기준 main43ed3b9. [상세 분석·검증 경계](../dev/issues145-150-v0181.md). 물리 X/Y59 PASS 재사용, 새 기기 시험0회; 사용자 ended 세션 재개 없음.
- #147 기존 task834ff88f 원시139span 확보: monotonic total10953.098ms, exclusive API spacing6439.104ms(58.8%) 최대. 보안 간격400ms/신선성/취소 유지. Voice/AI/transport 미측정, 개선율 미입증이므로 OPEN.
- #145 exponential 최대1초 감지 공백을16ms visibility poll/2초 budget으로 교체, ended/epoch 보호 유지. Galaxy 즉시 표시 해결 판정은 단회 후속 확인까지 NOT_TESTED.
- #146 사용자 touch 중 hold 차단·no-replay 테스트 추가. 기존 host 재생성/회전 UI 회귀 필수, X/Y59 물리 재시험 금지.
- #150 위조 signer/Boolean duration 허용을 실패 주입으로 재현 후 수정. Python39 PASS. 역사 PR artifact digest·보고서2734건 파싱 완료, merge-checkout SHA와 PR HEAD 구분. raw XML 독립 재파싱은 별도 test-reports artifact 필요.
- 로컬 Kotlin Gradle network unreachable, pytest 미설치. exact-source CI 검증 전 병합/릴리스 완료 주장 금지. 네 이슈 OPEN; #117/#76 분리 유지. 다음: 통합 PR Security/UI→main→기존키 서명→공개검증→설치1회→영향 최소 물리 수용. 3회 smoke와10+10 통계 구분.

## 2026-10-24 11시 이후 — 새 Work 개발 실행 인계 (#145/#146/#147/#150)

**현재 확정된 기기 기준:** ClosePaw v0.1.80 / versionCode81, 앱 응답 정상, self-update 설치 확인. 마지막 UI 세션 `622b622c-4d0c-4708-a690-d845e10e90c0`은 **ended/epoch8** (종료 성공). 다른 Work는 새로운 사용자 명시 승인 없이 재개하지 않는다. 태그/APK/서명 원본은 운영 runbook에서 재검증한다.

**완료·재시험 금지:** #146 Galaxy 실제 아일랜드 (393,309)→(43,59) 오차 X0/Y0 및 원위치 복원 (393,309), 과거 Y+67px 결함 이번 버전 재현 안 됨 ([증거](https://github.com/al-hub/closepaw/issues/146#issuecomment-6378386878)). #147 삼성 계산기 1→9 단회 성공 및 결과 `123,456,789`, 물리 X 3 표시 시 직접 누름, 완료 2단계·3단계 중단·후속 6단계 미실행·실행기 중단 확인 ([증거](https://github.com/al-hub/closepaw/issues/147#issuecomment-6378595204)); 3번째 클릭의 최종 외부 효과는 불확실, no-replay 유지. 기존 v0.1.79 실기기 성공과 10회 정확도 등은 원 버전 역사 증거.

**#147 미완료:** v0.1.80 로컬 단회 약11.239초; v0.1.79 역사 10회 평균 약10.964초와 단순 비교 +2.5%, 성능 개선/회귀 인과 추정 불가. 사용자는 **신규 간소화 기기 시험 3회 상한**을 요청. 이전 3회 자동 시도에서 최종 표시 `123,456,789`는 확인했으나 3회별 완료·시간 원시 증거 미확보: PASS로 승격 금지. #150 README/performance validator의 원래 **동등 조건 baseline/candidate 각10회 이상** 엄격 통계 요건과 3회 smoke는 별도 범주로 명시하고, 무단으로 기준 낮춰 전체 PASS 선언하지 않는다. main `GenericControlSequence.kt`의 초기 관찰→각 digit fresh guarded action 및 trace, `GenericControlOperations.kt`의 local monotonic/idle 계측 대조; Voice 요청→AI 계획/대기→도구 전송→Android 수신→검증/실행→결과 반환→음성 응답의 clock domain별 병목 실측 및 top stage 최적화 필요. [정적 분석](https://github.com/al-hub/closepaw/issues/147#issuecomment-6378690694), [3회 정책](https://github.com/al-hub/closepaw/issues/147#issuecomment-6378675374).

**#145 미완료:** 잠금 해제 직후 X/아일랜드가 **잠시 후 표시**됨(사용자 v0.1.80 재관찰); UI eventually restored지만 즉시 표시 FAIL. `ControlUiRestore.kt`의 [0,16,32,64,128,256,500,1000]ms backoff(최대 누적 1996ms+작업시간), `AgentService.kt`의 trigger/restore_id/keyguard_wait/unlocked/restore_requested/attached 타임스탬프를 상관 분석. 실제 delay가 keyguard 대기인지 attachment인지 또는 broadcast 지연인지 원시 로그 없이 단정 금지. 상세 진행 UI·서비스 재생성 단회 회귀도 이슈 본문 기준 확인. [정적 분석](https://github.com/al-hub/closepaw/issues/145#issuecomment-6378690333). 인증 입력 보호·epoch/ended/no auto resume 유지.

**#146 미완료:** 위치 영속화(서비스 재생성/회전), 현 Galaxy 경계/clamp, 드래그 stale·취소·시간초과·실물 터치 경쟁 fail-closed의 코드·자동 회귀와 문서 증거. 위에 통과한 Y59 물리 시험·일반 드래그 10회는 반복하지 않는다.

**#150 미완료:** `scripts/acceptance.py` / `qa/acceptance/catalog.json` 계획·증거 검증/보안 필수 회귀 및 CI artifact 독립 파싱, missing/skipped/failure/미분류 변경/위조 샘플 실패 주입, signer/source/version 분리, REUSED_EVIDENCE 정확성 확인. 코드/README 정적 검토만으로 자동 체계 수용 완료 주장 금지. [검토](https://github.com/al-hub/closepaw/issues/150#issuecomment-6378675692).

**다른 이슈로 이미 이관:** TalkBack ON, 알림/잠금화면 알림·장기 반복/재부팅 내구성 → #117; 다른 기기·폴더블 → #76. 재이관/중복 생성 금지. #145/#146/#147/#150은 현재 OPEN. 우선 구현 순서: #147 관측 구간 분리 및 실제 최대 병목 수선 → #145 unlock UI 지연 진단/개선 → #146 남은 자동 안전 회귀 → #150 독립 QA 실패 주입. 상호 연관된 코드 수정은 하나의 PR에 모으고 PR CI→main CI→기존 운영 서명키 단 1회 APK 발행→독립 signer/SHA/manifest 검증→설치 단 1회→영향받은 최소 물리 수용만 수행. Android 실기기 작업은 필요한 경우에만 사용자 명시 제어 허가; X·잠금 보안 정책 우회 금지.

**즉시 실행 지침:** `AI.md`, `AGENTS.md`, 본 인계, `doc/dev/github-signed-release.md`, 각 이슈·최근 댓글을 읽은 후 위 잔여만 구현. 완료 판정은 증거가 있는 수용 항목만; 더 이상 같은 설명만 반복하지 말고 가능한 독립 정적·자동 작업을 끝까지 진행. 현재 docs-only 인계 커밋은 배포 후보 새 APK/버전 변경이 아니므로 운영 Release 재발행 불필요.

## #150 자동 수용 검증 + #145/#146/#147 통합 후보 v0.1.80/code81 (2026-10-24 KST)

- 현 Work 기준 main `aa24bb145a9a9e318c19fd3284091ad8d8323412`. 운영 설치본 v0.1.79/code80의 기존 증거는 보존한다.
- #145 bounded unlock UI restore/monotonic attachment telemetry, 실제 phase/Gemini 단계 상세 표시. #146 명시적 full-display screen 좌표·실제 X 우선/cutout 보호 및43,59 원시 창 회귀. #147 동일 SHA hex 최적화·structured cancellation→durable task handoff, local stop과 외부 효과 불확실성 구분.
- #150 신규 단일 QA 이슈: 변경 영향·의존성 테스트 선택, 역사 증거와 새 검증 분리, XML 누락/skip fail-closed 보고서,10+10 host benchmark/통계. 전체 Security CI 및 기존 UI 릴리스 게이트 유지.
- 초기 get_status0.1.79/code80 reachable. session.status는 기기 로컬 실행 권한 OFF로 거부되어 세션/조작/설치 재시도 없음. 로컬 Gradle 다운로드 네트워크 불가; Kotlin/Android 검증은 GitHub CI에서 수행.
- #117 TalkBack/장기 반복·알림, #76 별도 기기/폴더블 이관 이미 반영. 중복 이슈/전송/새 키 생성 없음.
- [구현·증거 경계·최종 게이트](../dev/issues145-147-acceptance-v0180.md), [자동 QA 운영](../../qa/acceptance/README.md).
- **후보 상태**: exact HEAD CI/서명 공개 APK/설치·Galaxy 최소 수용을 확인하기 전 완료 선언하지 않는다. #145/#146/#147 OPEN 유지. 아래 v0.1.79 발행 이력은 역사 기록이다.

## #145/#146/#147 통합 수정·계측 v0.1.79/code80 발행·설치 (2026-10-23 KST)

- [PR #148](https://github.com/al-hub/closepaw/pull/148), 최종 HEAD d8640b1458b4490ce4443ec108fb9ed594d7fc24; merged main/APK/tag 정확 source **ed46c6a7e39b04e10483e4c501e4b1530fd2026d**.
- #145 같은 location unlock 복원·이전 소유자/observer 정리·안전한 동작/상태·숫자 sequence 상세 진행·알림 제어종료. #146 measured full-rectangle reflow/복원 clamp·X 우선·기존 X 경계 보정. #147 correlation/step/nested monotonic trace 및 cancellation finally 증거; 보안 probe/spacing/epoch/fingerprint/no-replay 유지.
- PR Security39957889906/UI39957889977, main Security39958925642/UI39958925680/Signed39959940340 모두 success. unit2717 실패/오류/skip0,lint오류0/경고120/info4,Python55+8/trust18. PR UI8 실패/오류/skip0·PNG4,실제 host 교체/회전/ended 회귀 포함. Galaxy 물리 수용으로 확대하지 않음.
- [Release/APK](https://github.com/al-hub/closepaw/releases/tag/v0.1.79):116630127 bytes, SHA256 **28ba835c82a870e4b2932f1e1c64a2c0087cc391bfdecd22861ffa8f0f402698**. 기존 signer d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba/v3. 공개 익명 download/checksum/manifest80/ABIs/두 runtime/R8/exact source 검증,artifact12503863290 내용 대조.
- 설치1회 task2b0a3df7-e3fb-4ed1-937f-b0656c20cf60: installing 뒤 재제출 없이 durable installed/current0.1.79/code80 → 독립 get_status0.1.79/code80 정상 확인. 최종 UI session **ended/epoch0**. 새 UI 세션·계산기·드래그·잠금·알림 물리 검증은 실행하지 않음.
- **#145/#146/#147 모두 OPEN 유지**. #145 OneUI 잠금 알림/실패·Gemini 상세/TalkBack, #146 실제 재생성·폴더블/경계·취소 수용, #147 새 trace1회→상위3개 확정→최적화→동일조건10+10/취소queue/E2E 잔여. Voice not_measured. 기존11초 단일/10회 데이터와 probe2627ms/tree1084ms는 baseline이며 개선율/확정 순위 아님.
- 사용자 종료 상태를 임의 재개하지 않는다. 다음 명시적 신규 기기 제어 요청에서만 새 측정한다. 같은 APK 재설치/태그 이동/불필요한 CI 금지. [종합 보고서·체크리스트](../dev/issues145-146-147-v0179.md), [원시 metadata](../dev/evidence/issues145-146-147-v0179.json).

## v0.1.78/code79 실기기 검증 종료 및 안정화 이관 (2026-10-23 KST)

- 사용자 승인에 따라 이번 **핵심 실기기 검증을 종료**하고 나머지는 #126·#140·#142 후속 안정화 범위로 이관. 코드/버전/APK/릴리스 불변, 새 세션 자동 생성 없음.
- 완료: #142 own-window 자동 드래그10/10 및 재사용·3px 거부, #126 삼성 계산기 동일 조건10/10(원시 표본과 통계는 아래), #140 사용자 잠금·화면 OFF→해제10회 후 동일 session_id/epoch 및 아일랜드 위치 유지, 첫/최종 사용자 육안 아일랜드1·X1 정상. Wi-Fi→모바일→Wi-Fi 복귀 뒤 앱/version/session 및 Tailscale VPN 연결 관찰 정상. 시각 피드백 제거는 사용자 육안으로 잔여 표시 없음 확인.
- 실행 중 X 중단: 계산기 초기화 후 1→9 sequence task `7b4d84f0-7e08-4e4b-89c0-1fe4ce74978b` 접수, X 이후 세션 `ended`/epoch8. 사용자가 **일부 숫자만 입력된 상태로 멈췄다**고 확인. 사용자 관찰 기준 중단 성공이며 도구의 `outcome_unknown` 원문 보존. 정확한 취소 단계·잔여 queue 폐기의 독립 타임라인 미확인. [#126 최종 코멘트](https://github.com/al-hub/closepaw/issues/126#issuecomment-6363896678).
- 후속 #126: E2E 음성/AI/네트워크별 latency 계측, `outcome_unknown`/작업 중단 확인 개선, 원격 대상 연결·실행 중 네트워크 회귀. #140: 잠금 중 보안·표시 및 서비스 재생성, window lifecycle/stale 확인과 상세 상태 라벨. #142: 물리 화면 경계, 진동, 회전/폴더블/TalkBack, X drag persistence. 모두 실제 미검증은 PASS로 승격하지 않으며 **OPEN 유지**.
- 휴대폰 제어 재개는 이후 새 사용자 명시 요청이 있을 때만. 같은 APK 재설치 및 불필요한 CI 실행 금지.

## v0.1.78/code79 물리 기기 후속 검증 (2026-10-23 KST)

- 앱 독립 get_status **0.1.78/code79** 정상. 재설치·재빌드·페어링 재설정 없음. 새 UI 세션 `96c2e7e0-e178-41f8-9d21-25485cf456e6` 시작 뒤 종료했고 독립 session.status **ended/epoch2** 확인.
- #142 물리 기기 production `island.observe → island.drag` 실제 좌표 변화 **10/10 성공**, 각 500ms hold/150ms move. 302×168 island, 1440×3120 display; 전후 좌표·task ID는 [원시 후속 증거](../dev/evidence/issues126-140-142-v0178-physical-20261023.json). 재사용 관찰 거부 `island_observation_required`/action_attempted=false, 새 관찰 3px 흔들림 거부 `invalid_island_drag`/action_attempted=false. 일반 화면 `dispatchGesture`가 아닌 own-window MotionEvent 검증.
- #126 삼성 계산기 `com.sec.android.app.popupcalculator`: 매회 C 초기화 후 1→9 로컬 sequence, 별도 screen.observe로 `123,456,789` **10/10 성공**. task 시작→완료(ms) 원시 표본 **11252,11254,11242,11286,11226,11244,11263,11213,11240,11163**; mean11238.3/P5011243/P9511275.65/P9911283.93/min11163/max11286 (percentile linear interpolation). 휴대폰 작업 실행 시간만 측정; 음성/AI/네트워크 분리 및 동등 조건 과거 10회 개선율 미측정.
- #140 화면 관찰의 owned_window_count=1 및 같은 세션 active→ended 확인. 아일랜드/X 실제 가시 개수·문구·진동·종료 후 window 제거는 독립 시각 증거 없음. 잠금·화면 OFF/복귀·서비스 재생성·실제 X 물리 종료·네트워크 전환은 실행하지 않았으므로 **미검증**. 관찰 수단 부족을 기능 실패로 표시하지 않음.
- #126/#140/#142 **OPEN 유지**: #126 전체 E2E 단계별 병목/네트워크/X 안전성, #140 삼성 잠금 전환 10회·UI/보안 실측, #142 물리 X/경계·잔여 수용 검증 필요. 앱 버전·APK/tag 불변, docs-only [skip ci].

# ClosePaw GitHub 정합성 감사 및 Works 인계

## #143 통합 변경 완료 — v0.1.78/code79 발행·설치 (2026-10-23 KST)

- [PR #143](https://github.com/al-hub/closepaw/pull/143), 최종 HEAD `26ed9b3753fa5384e4f08499cd8e576b403b83b3`; 병합·APK·태그 실제 source `a56ea0d6d61bc5c8be15af254d941f5fb4a075ed`.
- #142 production island.observe/drag: 로컬 동의·명시적 활성 세션·UI mutex·single-use60s 관찰·durable dedup·>=450ms hold/move/release·전후 실제 좌표 판정. 자체 창 MotionEvent 경로이며 OS dispatchGesture/삼성 물리 제스처 증거로 과장하지 않는다. X/물리 입력/잠금/세션 변경/취소/타임아웃에서 중단, unknown/no-replay 유지.
- #140 아일랜드 작업/앱명/오류 원문 비노출, 같은 owner의 잠금/해제 표시 전환, 세션 보존과 지연 작업 완료 거부. 잠금 위 표시/인증 우회/사용자 종료 후 자동 재개 없음.
- PR Security39938814385/UI39938814477, main Security39939738598/UI39939738671/Signed39940690741 모두 success. main JUnit2712/실패·오류·skip0, lint오류0, UI7/실패·오류·skip0·PNG4. 10회 production drag·10회 power/keyguard 전환·중단/재실행 거부 포함.
- [Release/APK](https://github.com/al-hub/closepaw/releases/tag/v0.1.78):116,613,743 bytes, SHA256 `2ca1e8009799d6c1084dcb9d98d03c0b310063631d8f8a3d307616442e051982`. 기존 signer `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba` / v3, 공개 익명 다운로드·checksum·manifest79·R8·두 런타임·불변 태그/정확 source 검증 완료(artifact12496455034).
- 설치1회 task `2cbc1e4b-945e-47b3-b776-7aa3b9b74e80`: durable installed/current0.1.78/code79 후 독립 get_status0.1.78/code79 확인. 기존9개 도구·권한 유지, island 명령2개 ready. 실기기 session은 ended 유지; island.observe가 control_session_ended/action_attempted=false로 거부함.
- main debug 에뮬레이터 island operation10회: P50=891ms/P95=P99=1077ms/평균900.5ms. **Galaxy 계산기/Voice/AI·네트워크 E2E 또는 개선율이 아니다.** #126의 실기기10회·구간별 계측/네트워크 회귀, #140/#142의 Galaxy 물리 수용은 미검증으로 OPEN 유지. 사용자 명시적 신규 요청 없이 ended 세션을 다시 시작하지 않는다.
- [최종 보고서](../dev/issues126-140-142-v0178.md), [원시 증거](../dev/evidence/issues126-140-142-v0178.json). 완료 기록은 docs-only로 발행 태그/자산을 이동·재생성하지 않는다.

## #141 완료 — v0.1.77/code78 네 가지 우선 과제 발행·설치·핵심 수용 (2026-10-23 KST)

- 범위: #139 비화면 실행/한정 복구, #128 단일 아일랜드·드래그, #126 로컬 sequence/지연, #69 Gemini 완료 인식. [계획·최종 근거](../dev/four-priorities-v0177.md), [PR #141](https://github.com/al-hub/closepaw/pull/141).
- TDD RED `ad47de351b258fc5b895d78cc309e3f0761f347b`; 최종 PR head `bcafdcf908cabd2c2d61c4211a9985c0ef20f3f2`; main/tag source `eaa943281495dd8ab19a6f977cd08dcc702b643e`. PR Security39797780567/UI39797780502 success. main Security39811521413/UI39811521593/Signed39812483379 success.
- [v0.1.77/code78](https://github.com/al-hub/closepaw/releases/tag/v0.1.77): 116,597,359 bytes; SHA256 `17760216f5002a0096d051502818a9c3a8bcde7043a46ea726dee39d1696b8f7`; existing signer `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`; R8, manifest, both native runtimes, immutable tag/exact source and public checksum verified (artifact12439499425).
- Install submitted once, task `99fd7724-bfef-4720-bf7b-9583b0c4feb1`; durable self-update installed/current and independent get_status both 0.1.77/code78. No reinstall, connector re-registration or data reset.
- Actual device: calculator `123,456,789` sequence 11,036 ms single sample; Gemini short exact reply45 about8.48s and long 20 paragraphs/2,160 chars with ending marker about25.20s, both complete/non-partial/no resend; #69 closed. User confirmed island1/X1 and both physical drags. User-confirmed locked state during five sequential non-UI Termux successes; UI-ended probes also succeeded.
- Retain #126 open for 10-run/P95 and interruption/screen/network regressions; #128 open for repeated physical/haptic/boundary/rotation/fold/TalkBack/recreation persistence, with production automated drag in #142; #139 open for lock+screen-OFF, reachable Windows SSH, forced-stop/reboot/network failure and duplicate prevention. SSH alias read timed out while Tailscale VPN/overlay was absent; one connection request was unconfirmed and the SSH command was not replayed. #117 owns long-run stability. Camera and Voice listening were not tested.
- The docs-only commit after the immutable release source does not move or rebuild v0.1.77. Do not reinstall the same APK or claim residual gates as PASS.

## #135 완료 — v0.1.76/code77 main 빌드 최적화·발행·휴대폰 설치 (2026-10-22 KST)

- [이슈 #135](https://github.com/al-hub/closepaw/issues/135), [PR #136](https://github.com/al-hub/closepaw/pull/136). immutable APK/tag source `f4dddbafece5acd0d6b5039950d84d830cffd21a`. 이후 docs-only 커밋은 발행 소스/태그를 바꾸지 않는다.
- extended icon 전체 의존성 대신 core24+원본 동일 vendor29/출처·라이선스·전체 소스 hash 유지. exact source/toolchain/flags/script native cache(main 단일 writer, 복원 metadata/hash/ELF 검증, miss/오염 시 source build), unit/lint/debug 단일 Gradle graph, 정상 cache cleanup 생략, release GC/Java RSS 계측 적용. 서명 신뢰 경계·기존 키·R8/축소·두 런타임·불변 태그·익명 공개 검증 유지.
- R8 347.51s→276.87s(-20.3%); main unsigned604s→545s, security461s→322s. main→공개653s→593s(10:53→9:53,-9.2%); 최종 PR→공개1461s→1086s(24:21→18:06,-25.7%). main runner1471s→1267s(-13.9%); PR 포함2707s→2002s(-26.0%). CPU/평균/P95 수치가 아니며 단일 표본이다.
- native 최초 source build106s + 실제 로컬 cache 삭제 후 원격 exact 복원3s 포함. changed-code warm 전체 릴리스는 미측정. 약8:10 추정은 실측이 아니며6~7분 목표는 미달. Full GC0/daemon pause 총17.862s와 host memory 여유를 근거로 heap 증설·R8 별도 JVM은 보류. configuration-cache release174/debug178 problems는 남아 있으며 라이선스 asset 정확성을 우선했다.
- PR Security39683757439/UI39683757480 success, unsigned skipped로 #134 조건 검증. main Security39684799317/UI39684799318/Signed39685929310 success. Android2665 tests/0 failures/errors/skips·lint0 errors119 warnings4 info, Python55+8, cache/provenance/gate18, UI6/4 renders, debug/release resolved graph 검증 통과. UI는 Samsung 물리 수용을 대신하지 않는다.
- [Release/APK](https://github.com/al-hub/closepaw/releases/tag/v0.1.76),116593263 bytes, SHA256 `9a77f807f5360535032d97e5cf8320adf09ed5abaa11ec3ac2f9ffaef80a363f`. 기존 signer `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`, manifest77/두 런타임/exact source/불변 tag/익명 다운로드 검증 완료(artifact12386227075).
- 설치1회 task `4288d196-c775-435e-bc8d-db53fe0f2408`. installing 후 교체 중 connector timeout1회, 재연결 뒤 durable installed/current0.1.76/code77 + 독립 get_status 정상 응답 확인. 업데이트·설치 권한 유지, 재제출/재등록/초기화 없음. 관측 시간은 transport/조회 대기를 포함한다.
- [최종 보고서](../dev/issue135-main-build-optimization.md), [원시 API/계측 증거](../dev/evidence/issue135-v0176-timing.json). 다음 후보는 남은 R8 입력/규칙과 configuration/plugin 비용의 격리 계측. 무근거 keep 삭제·heap/SDK 업그레이드나 효과 과장 금지. #126 앱 지연과 #128 Samsung GUI 물리 검증은 별도 OPEN 유지.

아래 #134의 “다음 개발에서 검증”과0.1.75 설치본은 당시 이력이다. 최신 설치본은0.1.76이며 #134 검증은 위에서 완료했다.

## 2026-10-22 운영 지침·main-only R8 조건 수정 — 검증은 다음 개발에 이관 (#134)

- 사용자 범위: 지침과 GitHub workflow 수정까지만. CI·서명·새 APK·휴대폰 설치는 실행하지 않는다.
- security-ci.yml의 unsigned-release는 main의 non-PR 실행으로 제한했다. 일반 PR의 Python/JVM/lint/debug 및 별도 UI 검증, main의 R8·서명·공개 검증 경로는 유지한다.
- AI.md에 수정 일괄 제출, 기존 결과 재사용, 완료 예상 시점 중심의 상태 조회, 중복 보고 최소화, 설치1회 후 재연결 여유와 durable→독립 버전 확인을 기록했다.
- 이번 변경은 [skip ci]로 저장하고 앱 버전은0.1.75/code76을 유지한다. 최신 설치·서명 검증 증거는 아래 v0.1.75 그대로이며 이번 조건은 미검증이다.
- 다음 실제 개발에서 새 버전/code와 함께 PR unsigned-release skipped/main unsigned-release success 및 기존 서명·공개 검증을 확인하고 #134를 갱신한다. 예상 발행18~20분/설치 포함21~24분은 아직 재측정하지 않았다.
- #126과 #128은 별도 유지한다.


## #132 완료 — v0.1.75/code76 발행·설치, 비용 후속 #134 (2026-10-21)

- [PR #133](https://github.com/al-hub/closepaw/pull/133), immutable APK/tag source `04c849b701c0d6a4c8dd332053619a7135db2584`.
- optimized unsigned release를 unit/lint/debug와 병렬화하고 정확한 성공 main CI artifact만 기존 키로 서명한다. 모든 테스트/lint/R8/두 런타임/기존 인증서/불변 태그/공개 독립 검증 유지. PR-only cancellation, 중복 security/** push 제거, task profile 추가.
- main Security39617725426/UI39617725429/Signed39619258531 success. Android2665 tests·lint0 errors, bridge55+8, provenance4, UI6/4 renders 통과.
- main→공개 검증1095s→653s(40.4% 단축). 최종 PR→공개1572s→1461s(7.1% 단축). main 이후 runner1465s→1471s(+0.4%); PR 포함2229s→2707s(+21.4%). PR의 R8 사전 검증 추가 비용은 [#134](https://github.com/al-hub/closepaw/issues/134)로 추적한다. 평균/P95나 CPU 절감으로 일반화하지 않는다.
- [APK0.1.75](https://github.com/al-hub/closepaw/releases/download/v0.1.75/closepaw-aa-bridge.apk), SHA256 `ad48eb9629e8be7551643b297aaeaa1dd35b29b431aff403d08ab64dac664319`,116585012 bytes. 기존 인증서·manifest76·두 런타임·익명 다운로드 검증 완료.
- 설치1회 task `de99172c-2a6e-4bb1-ba3b-b66a40fece89`; 교체 중 timeout 후 durable installed/current0.1.75/code76, 독립 get_status 정상 응답 확인. 재제출/재등록/데이터 초기화 없음.
- [비교표·세부 계측·한계·롤백](../dev/issue132-pipeline-performance.md), [API 증거](../dev/evidence/issue132-v0175-timing.json). 완전 의존성 cold-cache/production artifact 변조는 실행하지 않았으며 주요 task cache miss와 신뢰 경계 negative tests는 통과했다.
- #126 앱 응답 지연과 #128 Samsung 물리 GUI 수용은 별도 OPEN 유지. 아래0.1.74는 당시 완료 이력이며 최신 설치본은0.1.75다. 이미 통과한 기본기능을 반복하지 않는다.

**서명 APK 발행 방법:** [GitHub Actions 서명 릴리스 실행서](../dev/github-signed-release.md).
다른 대화/Work는 로컬 키나 SDK를 요청하기 전에 이 문서를 읽는다. 기존 secrets와
main Security CI → Signed Release 자동 경로, dispatch 없는 예약 fallback,
공개 APK 검증과 실제 설치 완료 기준을 포함한다.

## v0.1.74/code75 X + Island signed release installed — #128 physical acceptance OPEN (2026-10-21)

- [PR #131](https://github.com/al-hub/closepaw/pull/131) merged as `c124f3f86994664c9685b883cb31e1796b38c894`, the immutable APK/tag source. One visible emergency X arbitrates local/remote sessions and stops both; the shared draggable Compose island also covers bridge-only status. Modern owned controls participate in observation security and reject AI touches over their bounds.
- Final PR Security CI39607783934 / UI39607783952, main Security CI39608950694 / UI39608950568, and [Signed Release39610112952](https://github.com/al-hub/closepaw/actions/runs/39610112952) succeeded. Six emulator UI tests include real service overlay MotionEvent injection, X/Island ten drags each, persistence/X redisplay, single-X arbitration and short-tap stop/cleanup. This is not Samsung physical input or accessibility dispatchGesture evidence.
- [Release](https://github.com/al-hub/closepaw/releases/tag/v0.1.74) / [signed APK](https://github.com/al-hub/closepaw/releases/download/v0.1.74/closepaw-aa-bridge.apk):116,539,549 bytes, SHA-256 `e408bc8ed554acfafb91deef1ac8c8067da9e68aeaf2167c146ff6a265429ada`. Existing signer `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`; anonymous public download, manifest75, both native runtimes and checksum independently verified, artifact12350816441.
- Exactly one update task `96622026-8dc2-49f3-9da9-a78e3ecae77b`: durable installed/current0.1.74/code75 and independent responding-app status confirmed. Update/install permissions preserved; no re-registration or repeated installation.
- **OPEN:** Samsung physical X/Island drag10/10, haptics, real window count across lifecycle/app transitions, collision avoidance, outside touch/IME, rotation/folding/TalkBack and service recreation. #128 stays OPEN; #119 physical acceptance is not silently marked complete. Ordinary active UI is X + read-only Island; earlier Pause/Resume/✓ GUI instructions below are historical and superseded.
- [Full evidence and limits](../dev/issue128-release-v0174.md). Preserve #118 and v0.1.67+ accepted functional baselines; do not repeat Wi-Fi/SSH, locked Termux, calculator/browser/Gemini basics absent a new regression risk. Camera and locked-screen acceptance excluded. Documentation-only followups do not move the released tag.


## v0.1.70/code71 Premium UX published — physical acceptance OPEN (2026-10-20)

- [PR #123](https://github.com/al-hub/closepaw/pull/123) merged. Calm Intelligence Light/Dark palette, ambient depth,56dp Korean Pause/Resume/Stop/Manual controls, independent top-right X, movable/collapsible panel, structured actual-state feedback, intended/actual target distinction, exact tap ripple and injected-path timing. Semantic scroll has direction only; no invented paths or new generic drag/double-tap operations.
- Existing user-priority epochs, guarded physical resume, no-replay and consent/security/result verification remain. Intent preview obtains a NEW security verdict before final native epoch/freshness validation. Late state/label feedback is rejected; screen-off/user-control/cancellation clears effects.
- Four independent **AI expert roles** completed design/source reviews. Actual4-panel render review found and fixed200% Pause truncation; corrected labels pass no-ellipsis tests and visual review. No human professional or physical-user acceptance is claimed.
- PR Security39393879629/UI39393879612, main Security39394905098/UI39394905106, Signed Release39395656700 succeeded.2632 JVM tests/0 failures/errors/skips, Python55+8,6 native View UI tests,4 corrected renders; lint0 errors/119 warnings/4 info.
- [Signed APK v0.1.70](https://github.com/al-hub/closepaw/releases/download/v0.1.70/closepaw-aa-bridge.apk):116,525,229 bytes; SHA-256 `97a35d89e34988a97c10caec05670361671cf6ac79b920f50c0b2b2e1c34fc42`. Anonymous public download, original signer, manifest/code71, native runtimes and immutable source/tag96cc7013 were independently verified (artifact12252261387).
- **Not installed in this Work.** Prior installed0.1.69 evidence remains historical; do not claim new-version device acceptance. #119/#122 stay OPEN for actual overlay routing/X/physical controls/TalkBack/IME/rotation/fold, motion/alignment/comfort/performance, and stopped-result receipt retention during in-flight teardown. #117 long-run backlog, Notes/Clock HOLD and camera/locked-UI exclusions remain. No plugin re-registration or data reset.
- Next device work: install the existing signed update through the established path, then test the currently unverified user-control/overlay cases with explicit session start and no blind replay. Preserve physical pause/end across reconnection; a fresh session is required after end.
- [Specification, audit, four reviews, actual renders, test evidence and final Pass/Fail/Not Tested matrix](../dev/premium-ai-human-ux-v0170.md). APK source is immutable96cc7013; subsequent documentation commits do not alter the released binary.

## v0.1.69/code70 delivered and installed — #118 fixed, #119 physical acceptance OPEN (2026-10-20)

- [PR #120](https://github.com/al-hub/closepaw/pull/120) adds visible postcondition verification, retained exact app selections and user-priority GUI/session control. [PR #121](https://github.com/al-hub/closepaw/pull/121) reuses the complete Gemini identity guard to verify a canonical app hosted by Google; no caller alias or automatic effect replay. Final immutable APK source `81451f026ed2d2748c035c97f1f6ee245423dff8`.
- Final PR CI39371629561, main CI39372119884 and Signed Release39372744855 succeeded. Python55+8, Android tests/lint, native/debug/signed builds and anonymous public APK verification passed. Original signer and manifest0.1.69/code70 confirmed.
- [Signed APK v0.1.69](https://github.com/al-hub/closepaw/releases/download/v0.1.69/closepaw-aa-bridge.apk):116,508,665 bytes; SHA-256 `c67d8e8122ff4a5dbc64939f585e97e1700dab7dd17c8afbe13848d82a6393c9`. One update task `dae5b18e-a05f-4e17-b56a-ae290895b700`; durable installed/current0.1.69/code70, connection and permissions retained.
- Actual installed-version Home observation, Notes launch and direct Notes→Gemini transition succeeded. Gemini was discovered BEFORE Notes, yet remained launchable without re-discovery/Home. Exact/native-attested destination verification returned app_result_verified=true/outcome_unknown=false. No input/send, settings or camera changes.
- GUI implements breathing user-wait, bottom-right pause/physical play, top-right permanent session stop, fresh serialized reobservation and invalidation of queued/old commands. Dedicated Gemini and direct read_app honor the user gate. Screenshot security probes and accessibility reads are labelled separately; target/scroll/result feedback is present.
- **#119 is not fully accepted yet:** human pulse appearance, physical pause/play/X, app-body touch, IME/rotation/TalkBack/reduced motion need direct confirmation. Session end→new-observe refusal was verified on v0.1.68. Do not claim physical button tests passed or silently restart/resume a user-stopped session.
- [Executed evidence/design/remaining steps](../dev/control-handoff-v0168.md). #118 closed; #119 remains open; #117 is long-run backlog. v0.1.67 baseline #114–#117 success is retained; no repeated Wi-Fi/SSH, locked Termux, basic calculator/browser/Q&A tests. Camera and locked UI excluded.


## v0.1.66 expanded camera-excluded acceptance — logs secured, residuals HOLD (2026-10-20)

- Installed0.1.66/code67 confirmed. Camera excluded by corporate policy; Notes/Clock fixes remain HOLD.
- Executed: Chrome visible-screen read; Kakao first-screen read; Maps empty-field input/public station result selection; one Gemini synthetic24-line question with all numbered lines/end marker collected and bottom screenshot verified; generic Gemini scroll after temporary grant; Termux exit0. Earlier same-version calculator click/tap result7 reused.
- Persisted Gemini stages completed10,285ms, reply collection4,235ms. Generic scroll547ms. Home/app transitions and connector wall time remain slower; no matched speedup percentage.
- Residuals: Samsung Internet content_missing; earlier app-list timeout; two model-popup taps and final calculator-return refused screen_changed before effect; Maps launch post-observation secure_window_changed/unknown resolved by observation without replay; two transient transport timeouts recovered without re-registration.
- Temporary app access revision5 rolled back to revision6; independent readback exactly matches original configuration. No camera/notes/alarm/message action.
- User did not directly observe the indicator. Visual appearance/removal, physical-touch, foreign-overlay, cancellation/interrupt and deliberate lock/secure-window device acceptance remain UNVERIFIED. Do not mark the whole acceptance suite passed.
- [Executed matrix, exact task IDs/timings and future instrumentation plan](../dev/acceptance-v0166-no-camera-20261020.md) and [redacted structured logs](../dev/evidence/v0166-no-camera-20261020.json). No raw private screen strings/images or app-private logcat published; source/release unchanged.


## v0.1.66/code67 delivered and installed — camera-excluded calculator/tap accepted; residuals HOLD (2026-10-20)

- User put existing Samsung Notes input and complete Clock alarm enumeration findings on **HOLD**. Prior0.1.65 camera save, calculator discount, Kakao first screen, Maps search and cross-app return evidence is retained; do not replay the camera or re-open HOLD work.
- [PR #111](https://github.com/al-hub/closepaw/pull/111) merged as16bdfb19885f5f548cfe511a25643f9eba4904b9. Adds a noninteractive cyan border/ClosePaw activity badge for generic remote UI and registered/Gemini paths, service event wakeups, single native validation, and immediate first screenshot probing with bounded explicit rate-limit retry. Security/consent/freshness/unknown-outcome protections remain.
- PR CI39333648884, main CI39334583679 and Signed Release39335426773 succeeded. Parsed PR artifact12226386374:2,596 Android tests, zero failures/errors/skips; lint zero errors/112 warnings/4 info. Python55+8, native/debug and signed builds passed. Initial compile-only hidden View property failure was corrected before publication.
- [Signed APK v0.1.66](https://github.com/al-hub/closepaw/releases/download/v0.1.66/closepaw-aa-bridge.apk):116,468,069 bytes; SHA-256 `d5cfebc81f0faf857ad26353ca50aece8a7abb7762c0cec682125382cc307d58`. Runner independently downloaded the public asset and verified original certificate, manifest0.1.66/code67, both native runtimes, checksum and immutable tag/source. Public evidence artifact12225624162.
- Exactly one remote update task13ec4ee2-bf75-4341-b335-fee952185a9a. Durable status independently confirms installed/current0.1.66/code67 after reconnection; update/install-source permissions preserved; no re-registration or data reset.
- Latest user excludes camera use due corporate security. Unlocked Home and calculator launch succeeded; guarded tap1 and clicks +,6,= returned final7. Device arithmetic totals653/529/572/542ms (median557ms); four submit/result pairs took28.7s wall time. Initial launcher click refused screen_changed/action_attempted=false; fresh observation resolved it. App listing still timed out. No camera/Notes/Clock action. Functional calculator/tap accepted; visual border/badge, physical touch pass-through, lifecycle/foreign-overlay/secure-lock acceptance and end-to-end speed comparison remain unverified and recorded HOLD.
- Old-version matched calculator baseline was not obtained due apps_query_timeout and a refused launch (action_attempted=false). Do not misclassify it as OS visibility or claim a percentage improvement. [Implementation, HOLD evidence and validation limits](../dev/remote-control-feedback-latency.md).

## v0.1.65/code66 delivered and installed — device UI acceptance blocked by lock (2026-10-19)

- [PR #110](https://github.com/al-hub/closepaw/pull/110) merged as `78395dcdb0637a81224cf14836695fc67de37f19`. Runtime declarative app access/configuration, diagnostic app discovery, bounded complete security traversal and separate filtered presentation are implemented. No app-specific UI branches or coordinates.
- Final PR CI39266632595: 2,582 Android tests, zero failures/errors/skips; lint zero errors/109 warnings/4 info; Python55+8 and debug/native checks passed. Main CI39267769756 and Signed Release39268830338 succeeded. RED CI39264765498 reproduced two expected failures.
- [Signed APK](https://github.com/al-hub/closepaw/releases/download/v0.1.65/closepaw-aa-bridge.apk): 116,454,881 bytes; SHA-256 `5af7920fed5c26a708b40744dd5b8a4c66e161b99553ec19fd74ef65ce131bd6`. Runner independently downloaded the public asset and verified manifest0.1.65/code66, original certificate `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`, both native runtimes, checksum and immutable tag/source. Verification artifact12195785243.
- Exactly one update task `bdc61eb6-a286-496f-ad11-c42a1bd2a3fe`: durable status installed/current0.1.65/code66 after reconnection; connection/update permissions preserved, no re-registration. Latest update check up_to_date.
- Same installed APK runtime acceptance PASSED: initial revision0/default4096/50; apply output_limit40 -> revision1 (task427f747e-675e-48f5-9bf4-9e5db4b684a0), independent readback, rollback -> revision2/default4096/50 (task6019e060-309f-475a-a557-f995e3489349), independent restored readback. App grants remain empty. No rebuild for these changes. Termux id task0f5de8c1-5d9e-4d55-b040-5dc68ab62043 succeeded exit0.
- First new-version screen observation returned `device_locked`, action_attempted=false/outcome_unknown=false. No new-version app launch, UI write or camera capture was attempted. This confirms refusal while locked, not unlocked functionality.
- Calculator/discount, Kakao first screen, enabled alarms, synthetic notes draft, public maps search, one camera capture/new-photo save verification, cross-app followups and current-version Gemini/Voice acceptance remain PENDING. Camera is authorized without scene-preparation requests; actual lock must first be released. Do not replay effects with unknown outcomes. P2/Voice and matched end-to-end latency remain OPEN.
- This checkpoint supersedes earlier candidate/delivery notes; historical evidence remains below. Documentation-only followups do not change the immutable released APK source.


## v0.1.64/code65 popup/model/question acceptance completed (2026-10-19)

- [PR #109](https://github.com/al-hub/closepaw/pull/109) merged as6703a873296a51c32985198e8dd63b6d8db9ff96. Observer now securely probes/scans OS parent/anchor-linked application windows and exposes only the topmost owned surface, with bounded text-free diagnostics. Same-package-only ownership is rejected; all prior consent/security/unknown-send guards retained.
- Actual device acceptance: nonfocused popup linked by Android parent_id to the active/focused Gemini host, complete129-node/depth33 traversal, real model options exposed. Generic selection of requested3.8 Flash verified by fresh Gemini Flash title AND actual checkmark in the reopened picker. One dedicated synthetic question then completed with sent=true/reply_completed/coverage=complete/outcome_unknown=false; final generic105-node observation confirmed model title and new reply.
- RED2,562 tests/1 expected assertion; GREEN2,571 tests/0 failures/errors/skips, lint0 errors/108 warnings, Python55+8 and native/debug passed. Main CI39254537754 and Signed Release39255478075 succeeded.
- [Signed APK](https://github.com/al-hub/closepaw/releases/download/v0.1.64/closepaw-aa-bridge.apk):116,443,909 bytes; SHA-25674345d0fb264dda6380443891f5247e2572163f53de025562fdbff0ab0da2d9f. Independent anonymous public download, original signer, manifest/code65, both native runtimes and immutable source/tag verified.
- One automatic update confirmed installed/current0.1.64/code65 and reconnection, permissions preserved, no re-registration. Termux id exit0 and latest check up_to_date. v0.1.63 locked-maintenance evidence retained; this version's pre-install sample was secure_window_changed, not a new known-locked acceptance.
- Immediate post-action window snapshots can race platform updates; uncertain results were resolved with fresh observations, not blind write replay. Back on a nonfocused popup can dismiss IME/background the app; selected/checked flags were unavailable, so visual checkmark verification was used. **P2/Voice and matched end-to-end/speech latency remain open.** [Full evidence and limits](../dev/owned-popup-observation-v0164.md). Earlier checkpoints below are historical.


## v0.1.63/code64 delivered and installed — unlocked model acceptance pending

- [PR #108](https://github.com/al-hub/closepaw/pull/108) merged as d9bb229398f4121a14fe668d9ecf5f7d5666b900. Explicit tap(observation_id,node_id) derives a point only from a revalidated clickable node; no caller coordinates or automatic click fallback. Complete tree, current window/rotation/region/occlusion, consent/security/unknown-send guards retained; clipped/covered targets, PiP, magnification and touch exploration refuse tap. Cancellation remains unknown and never replays.
- RED2,550 tests/2 expected assertions; GREEN2,559 tests/0 failures/errors/skips, lint0 errors/108 warnings, Python55+8, debug/native and signed probe passed. Main CI39226838487 and Signed Release39227790943 succeeded.
- [Signed APK](https://github.com/al-hub/closepaw/releases/download/v0.1.63/closepaw-aa-bridge.apk):116,431,913 bytes; SHA-256844c71c3157b09384ed0675860e9ea44cf83c7e826789f4df827f927c3d676dd. Anonymous public download, original signer, manifest/code64, both native runtimes and immutable source/tag verified.
- Exactly one remote update: confirmed installed/current0.1.63/code64 after reconnect, permissions retained. New tap schema rediscovered. Termux id succeeded exit0 between post-install device_locked observations; latest check up_to_date. No repeated install or UI action while locked.
- The user's physical touch proved the picker exists; requested target3.8 Flash. Native title click still showed no menu after IME dismissal. New-version tap/model selection and the subsequent question remain UNVERIFIED because the device is locked. P2/Voice remains open. [Evidence and immediate unlocked steps](../dev/observed-node-tap-v0163.md). Earlier checkpoints below are historical.


## v0.1.62 real sidebar/composer read accepted (2026-10-19 17:18 KST)

- After user unlock, generic Gemini search/select/open_app succeeded. Generic sidebar open changed the actual screen; registered Gemini screen read succeeded while already foreground, untruncated and text_read_status=ok.
- Actual identity diagnostics: navigation_host_visible=true and identity_verified=true; complete176-node/depth33 traversal, no protected input. Content/chat pane flags remained false, confirming why their earlier-only gate rejected the sidebar.
- Generic close succeeded; registered read on the normal104-node composer screen also succeeded. The prior sidebar target_not_foreground issue is accepted as resolved for this verified version/screen.
- No new model-title click was replayed. Fresh metadata exposes no expand/collapse. Remaining model-menu step requires comparing a direct user touch with the earlier acknowledged-no-visible-change native click before selecting an actual requested model.
- Device totals: launch1,686ms, sidebar open1,141ms/close1,012ms; no matched end-to-end/speech improvement claim. **P2/Voice and model selection remain open**. [Actual evidence and next step](../dev/generic-navigation-host-v0162.md). Earlier checkpoints below are historical.


## v0.1.62/code63 delivered, installed and checked while locked

- [PR #107](https://github.com/al-hub/closepaw/pull/107) merged as efb4bb54ac3173c32bf49fa87feaf60dfe43a30e. The actual native navigation host is accepted only with the exact activity anchor/package and visible host; hidden-host/anchor-only/foreign-package fixtures remain rejected. navigation_host_visible diagnostic added.
- RED2,548 tests/1 expected failure; GREEN2,548 tests/0 failures/errors/skips, lint0 errors/108 warnings, Python55+8 and signed probe passed. Main CI39211074900 and Signed Release39211675610 succeeded.
- [Signed APK](https://github.com/al-hub/closepaw/releases/download/v0.1.62/closepaw-aa-bridge.apk):116,425,705 bytes; SHA-256 37f17d786b468273d9a7c908368ac0e638b184f7d5702c7c3bcf291f8d48eaf9. Anonymous public download, original signer, manifest/code63, both native runtimes and immutable source/tag verified.
- Exactly one remote update requested after device_locked observation. Installed/current0.1.62/code63, connection and update permissions confirmed. Further lock observations bracketed a successful Termux id task (exit0); latest check up_to_date. No UI bypass or repeated install.
- Actual v0.1.61 sidebar read failure ruled out the prior activity-visibility hypothesis: activity anchor visible, complete176-node traversal/depth33, no protected input, but content/chat pane visibility flags false. Existing native metadata verified the exact shared navigation host after closing the sidebar; the new-version open-sidebar read is still PENDING because the phone is locked.
- Generic Gemini/Chrome search/select/launch was accepted earlier. Model title produced no visible picker through native click; do not replay or claim selection. **P2/Voice ACTIVE**. [Evidence and immediate unlocked entry point](../dev/generic-navigation-host-v0162.md). Earlier status sections below are historical checkpoints.


## v0.1.61/code62 delivered and remotely installed — unlocked acceptance checkpoint

- [PR #106](https://github.com/al-hub/closepaw/pull/106) merged as e8c6a9da8f801887da0563269315a7bf2d7697e1. Read-only identity now separates a structural activity anchor from an exact visible Gemini pane and exposes bounded boolean/count diagnostics. Composer/write/send and generic action policies remain unchanged.
- RED2,546 tests/1 expected failure; GREEN2,546 tests with0 failures/errors/skips; lint0 errors/108 warnings; Python55+8 and signed probe passed. Main CI39203887423 and Signed Release39204521059 succeeded.
- [Signed APK](https://github.com/al-hub/closepaw/releases/download/v0.1.61/closepaw-aa-bridge.apk):116,425,565 bytes; SHA-256 7111665c9bc35931fa8b9effa97dac63d97ca095d84323a997337a2c9ee0405e. Anonymous public download, original signer, code62, both native runtimes and immutable tag/source verified.
- One automatic update requested after device_locked observation; installed/current0.1.61/code62 and reconnection confirmed, with device_locked observed again. A Termux id task succeeded exit0 between further lock observations; latest check up_to_date. No repeated installation/wake/unlock.
- Earlier unlocked v0.1.60 generic label search/exact selection/open_app succeeded for Gemini AND Chrome. Registered read works on composer but refused sidebar; model-title native click showed no visible change. Current device UI totals933–1,876ms do not establish end-to-end/speech latency improvement.
- New-version sidebar read and model selection remain unverified because the phone relocked during CI. Runtime identity-cause hypothesis remains unproved; **P2/Voice ACTIVE**. [Evidence and next entry point](../dev/generic-control-device-v0161.md). Earlier status sections below are historical checkpoints.


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


## v0.1.57 generic startup acceptance blocked / v0.1.58 correction in progress

- User enabled the independent Android screen control switch; all3 generic operations report ready on installed v0.1.57/code58.
- First current-screen observation returned truncated=true/actionable=false; filtered app discovery found the intended Gemini target.
  No node write was attempted from the partial screen. Old responses do not expose which traversal/output cap caused truncation.
- v0.1.57 refuses Home/app launch along with node actions, so a bounded partial starting screen cannot begin generic navigation.
  [PR #103](https://github.com/al-hub/closepaw/pull/103) starts with RED regression cases, then shares a guarded Home/exact app-launch policy
  between the common engine and native boundary. Back and all node-targeted operations still require completeness.
- Add depth/count/truncation-cause metadata; traversal depth64 retains512-node and50-output caps. Candidate v0.1.58/code59 is not delivered.
- A separate dedicated Gemini READ did not verify foreground identity; the next generic read returned secure_window. No bypass/retry/send.
  These results are distinct from the startup truncation defect. Real acceptance requires an unlocked ordinary screen after delivery.
- **P2 ACTIVE**. Model choices/selection, one dedicated question/new reply, second-app navigation/scroll and Voice acceptance remain pending.
  [Evidence and correction](../dev/generic-navigation-startup-v0158.md).


## v0.1.57/code58 delivered and remotely installed — generic device acceptance pending (2026-10-19 KST)

- [PR #102](https://github.com/al-hub/closepaw/pull/102) merged. Immutable release source/tag target: `2cbdb1a10fe81dd2f7f36dd3bef59eff21f15300`.
- Android **2,521 tests**, zero failures/errors/skips, including28 new cases. Lint **0 errors/108 warnings**; Python Termux55 + ChatGPT bridge8 passed.
  PR Security CI39127838851, signed native probe39127838744, main Security CI39167500480 and Signed Release39168050184 succeeded.
- [Published APK](https://github.com/al-hub/closepaw/releases/download/v0.1.57/closepaw-aa-bridge.apk):116,414,765 bytes;
  SHA-256 `7ef114d80b5aa7c46ff7adf85d7bfaf7e190bbee23a95545492e36364a1a30b6`.
  Anonymous public downloads on the release runner matched the built APK and checksum; manifest ai.closepaw/0.1.57/code58,
  original certificate, APK v2 integrity, both native runtimes and source/tag were verified. GitHub asset digest matches.
- **Remote update executed exactly once**, task `882ca03b-1c93-4018-a3c9-3e896454b700`.
  First status was installing on v0.1.56; later app response confirmed **installed/current v0.1.57/code58**.
  Remote-update consent and install-source permission remain true. The same existing MCP connection responds after replacement;
  no plugin re-registration, app removal, data reset or re-pairing was performed. This does not establish every retained setting or a warning's root cause.
- Installed discovery exposes `android.screen.observe`, `android.apps.list`, `android.ui.action`. All report `generic_control_disabled`,
  confirming the independent opt-in remains OFF. A read-only observe request was refused before screen capture, as designed.
- **Required user step:** ClosePaw Settings → ChatGPT Voice → **Allow ChatGPT Android screen control** ON, then leave an unlocked ordinary screen visible.
  Gemini model choices/requested selection and re-observation, one dedicated question/new reply, second-app navigation/scroll and Voice latency remain PENDING.
  Do not mark generic real-device acceptance or P2 DONE based on APK delivery/update. **P2 ACTIVE**.

[Detailed evidence and resumption instructions](../dev/generic-android-control-v0157.md).

## v0.1.55/code56 remote self-update APK delivered — device acceptance pending

- User explicitly approved public source publication and release delivery in this conversation. [PR #100](https://github.com/al-hub/closepaw/pull/100) merged; immutable APK source/tag target `19593ea0556be272a8eadffe5835de976539e031`.
- PR [Security CI](https://github.com/al-hub/closepaw/actions/runs/39119481080), main [Security CI](https://github.com/al-hub/closepaw/actions/runs/39119872501), and [Signed Release](https://github.com/al-hub/closepaw/actions/runs/39120247413) succeeded.
- Downloaded published [v0.1.55 APK](https://github.com/al-hub/closepaw/releases/download/v0.1.55/closepaw-aa-bridge.apk): 116,397,169 bytes; package `ai.closepaw`, version `0.1.55`, code56. SHA-256 `d9048083a8cbef2ca8f74fca3145472ad15791c50ad80db2d5451fcff6c09f56` matches published checksum and GitHub asset digest. Independently verified APK v2 signature AND whole-file chunk digest, original certificate `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`, both native runtimes and new install permission.
- Android report: **2,493 tests, zero failures/errors/skips**, including16 new policy/ledger/operation tests. Python bridge55+8 also passed. These are not an installed Android PackageInstaller/approval test.
- Manual update buttons remain. Added fixed-envelope operations `app.self_update.check`, `app.self_update.install`, `app.self_update.status`; no MCP tool re-registration. Separate local remote-update consent defaults OFF.
- **First install manually** using Settings → Software Update. Capture the actual security warning before deciding the remedy. Then enable “Allow updates requested through ChatGPT” and allow installation from ClosePaw if required.
- After installation, discover operations and query status to verify version56, local consent and install-source permission. This establishes preparation only. Actual unattended replacement/reconnect requires a **later higher version code** and separate device acceptance. Do not claim full automation, warning removal or successful device installation based on this release.
- System-required approval is reported; Play Protect/Samsung checks are not bypassed. Unknown installation outcomes block automatic resubmission; query target installed version after reconnection. Existing pairing/settings and manual workflow remain.
- The user's plan: capture the warning during this manual update, then verify readiness before attempting a later remote update. Broader Gemini/P2 acceptance remains unchanged.



## Installed v0.1.54/code55 — long-answer acceptance passed (2026-10-18 17:38 KST)

- Device status confirmed v0.1.54/code55. Task `e242dd43-78ac-424a-8033-cf53f4bce7c9`, request `gemini-v0154-long-20261018-1738-01`: `succeeded/reply_completed`, `sent=true`, `partial=false`, `coverage=complete`, `completion_evidence=paged_start_overlap_end_and_stable`. One submission, no resend.
- All40 requested items (301 through340) and every double/triple value were checked against the returned text; no missing/duplicate item or truncated output. Final foreground page had a visible completion footer and no forward action remaining.
- Collector duration **14,876ms**, accepted task→terminal **17,014ms**. This includes generation and scrolling; it does **not** measure visual-generation-finished→return or spoken-result latency.
- `pages_collected=1` counts one distinct whole native body, not one physical viewport. It is not a claim about measured scroll count or three-screen coverage.
- A separate unique short task `cc728e70-b410-49b4-b960-f9758bf9a694` submitted once and retrieved its exact requested text, but ended partial/`device_locked` at collection39,819ms. Its completion and coverage were **not** established. No retry or resend after the lock.
- Long-answer content and automatic terminal completion are now verified for this test. Perceived completion delay, uninterrupted short-answer acceptance on this version, Voice return and continuous-use reliability remain open; **P2 ACTIVE**. Stop device testing after the lock and observe ordinary use before claiming broader acceptance. Earlier pending-device/release statements below are historical.

[Detailed implementation and evidence](../dev/gemini-identical-body-paging-v0154.md).


## v0.1.54/code55 signed correction delivered — device acceptance open

- Installed v0.1.53 retrieved all40 requested items (201–240, arithmetic verified), 1,238 Unicode code points, in6,554ms. It still ended partial/reply_scroll_stalled after its first scroll; coverage and automatic terminal completion were unconfirmed. One send; no resend. Perceived latency is unverified.
- PR #99 permits bounded continued scrolling for the invocation's identical exact-question-owned native whole body until its visible footer. Generic stalls, limits and safety checks remain. [Evidence and verified delivery](../dev/gemini-identical-body-paging-v0154.md).
- Android **2,477 tests**, zero failures/errors/skips; PR/main Security CI, signed native runtime probe and Signed Release succeeded.
- [v0.1.54 APK](https://github.com/al-hub/closepaw/releases/download/v0.1.54/closepaw-aa-bridge.apk) independently downloaded and verified: package/version/code55, checksum/GitHub digest, previous signer, native runtimes and immutable tag target `1e580e353d0da3f9cc6c1778f79a81a482b7cdd6`.
- **Installed-v0.1.54 automatic completion, long-answer coverage and perceived latency acceptance remain pending. P2 ACTIVE.** Earlier pending-release statements below are historical; successful delivery does not close the device gate.


## v0.1.53 full test body captured / v0.1.54 completion correction in progress

- Installed v0.1.53/code54 test captured all40 items (201–240, arithmetic validated) in6,554ms, but ended partial/reply_scroll_stalled with unconfirmed coverage; one send, no resend.
- Whole native body stays equal while scrolling toward its footer. The old collector stopped after its first action. [Evidence and bounded continuation correction](../dev/gemini-identical-body-paging-v0154.md).
- CI, release and installed-v0.1.54 acceptance pending. Automatic completion and perceived latency remain unverified. **P2 ACTIVE**.


## v0.1.53/code54 signed delivery verified — device acceptance pending

- v0.1.52 long-answer test failed after one send: 119,827 ms, zero collected pages, no answer returned. The new response existed offscreen while the old body/toolbar remained visible; no resend occurred.
- PR #98 adds exact native current-question/response ownership and captures its offscreen body while requiring its own visible completion controls. [Diagnosis, regression coverage and delivery evidence](../dev/gemini-native-offscreen-v0153.md).
- Android **2,472 tests, zero failures/errors/skips**; PR/main Security CI, signed native runtime probe and Signed Release all passed.
- [v0.1.53 APK](https://github.com/al-hub/closepaw/releases/download/v0.1.53/closepaw-aa-bridge.apk) independently downloaded and verified: package/version/code, checksum and GitHub digest, previous signer, native runtimes and release tag target `feecbe5c23a2dd545c3d3c9568d6b1f871489bca`.
- **Installed v0.1.53 long-answer coverage and perceived latency remain pending. P2 ACTIVE.** v0.1.51/v0.1.52 automatic long-answer acceptance failed; CI does not close that gate. Earlier pending-release notes below are historical.


## v0.1.52 device failure / v0.1.53 native response ownership in progress

- v0.1.52/code53 installed and tested: one send, then 120-second collection timeout; zero pages and no answer returned. Long-answer acceptance remains **FAILED / OPEN**.
- Read-only device metadata confirms the new native response exists offscreen while the old response/toolbar is visible. The later screen read exposed the old body and is not acceptance evidence.
- [Diagnosis and native ownership correction](../dev/gemini-native-offscreen-v0153.md). CI/release/new installed-device acceptance pending. **P2 ACTIVE**.


## v0.1.51 device result / v0.1.52 signed correction delivered

- Installed v0.1.51 verified. Short answer automatic round trip succeeded; completion evidence→collection was 585 ms, but full perceived latency is unverified.
- Long request was submitted once, then collection stopped after 783 ms on an empty pre-generation viewport (`reply_end_unconfirmed`). No resend. Automatic long-answer acceptance **failed**, despite successful CI/signed delivery.
- [Evidence and correction](../dev/gemini-paging-startup-v0152.md): wait for body/footer instead of treating absent forward scrolling as an end condition.
- [v0.1.52/code53 APK](https://github.com/al-hub/closepaw/releases/tag/v0.1.52) published and downloaded/verified. Android **2,464/2,464**, main CI and signed runtime probe passed; manifest/signer/checksum/native runtimes/tag verified.
- Installed v0.1.52 long-answer and perceived latency acceptance remain pending. The v0.1.51 long-answer failure is not closed by CI alone. **P2 ACTIVE**.


## Gemini reply latency + long-answer paging — v0.1.51 signed delivery verified

- User-authorized combined improvement: shorten completion/return latency and collect below-viewport answer text.
- Current baseline: v0.1.50 basic text/Voice round trip confirmed; continuous-use reliability remains partial.
- Implementation and acceptance scope: [v0.1.51 notes](../dev/gemini-reply-latency-scroll-v0151.md).
- [v0.1.51 / code 52 signed APK](https://github.com/al-hub/closepaw/releases/tag/v0.1.51) published and independently downloaded/verified.
- Android **2,457/2,457**, main Security CI and signed runtime probe passed; signer/manifest/checksum/native runtimes/tag verified.
- User conversation records confirm the baseline Voice round trip. New v0.1.51 short-answer timing and long-answer coverage device acceptance remains pending; **P2 ACTIVE**.
- No background/locked operation claim. This entry supersedes older pending-Voice/release notes below.


## 실기기 자동 왕복 확인 — 2026-10-18 10:06 KST

- **v0.1.50/code51 업데이트 설치 및 자동 질문→전송→이번 새 응답 수집 성공**.
- task `748530f3-b610-4098-a4e3-accec89a6193`, request
  `gemini-v0150-acceptance-20261018-1006-02`: `succeeded`, `reply_completed`,
  `sent=true`, `send_action_invoked=true`, `outcome_unknown=false`, `partial=false`,
  `completion_evidence=semantic_complete_and_stable`. 접수→완료 약 6.8초.
- 첫 검사 task `d32984aa-4d83-4e8a-bd10-bd4334525b51`는 전송 동작 후
  `target_changed`/`outcome_unknown`으로 중단됐다. 이후 READ에서 전송된 질문과 응답을 확인했고,
  사용자는 검사 중 ChatGPT로 화면을 전환했다고 답했다. 기존 요청은 재전송하지 않았다.
  화면을 유지한 두 번째 별도 요청은 자동 수집까지 성공했다.
- **현재 접근성 경로는 잠금 해제 및 수집 완료까지 Gemini 전경 유지가 필요하다.**
  이 결과는 백그라운드/잠금상태 실행, 긴 응답, 같은 Voice 대화 인수를 보장하지 않는다.
- **P2 ACTIVE**: 텍스트 경로의 기본 자동 왕복은 확인했으며 같은 Voice 인수는 남아 있다.
  APK/서명/태그는 변경하지 않았다. 실제 질문·응답 원문과 개인정보는 공개 기록에서 제외한다.
- 이 절이 아래 발행 당시 설치·자동 왕복 대기 기록보다 최신이다.

기준: **2026-10-17 KST**, `al-hub/closepaw`의 main, 열린 이슈/PR 전체,
최근 병합 PR, Actions, 릴리스, 진행표 및 운영·인수 문서를 대조했다.
GitHub Projects의 실제 필드·자동화 설정은 이번 감사에서 읽거나 변경하지 않았다.

## 최신 전달 상태 — 2026-10-18 KST

- **v0.1.49/code50 설치 확인**. PR #94의 발행·서명·체크섬 검증 결과는 [발행 기록](../dev/gemini-roundtrip-v0149.md)에 보존한다.
- 실제 자동 질문은 **`send_unavailable`, `sent=false`**로 중단됐다. 이후 사용자 수동 전송 뒤 기존 `read_app`으로 실제 응답과 완료 툴바 READ에 성공했다. 자동 왕복·같은 Voice 인수는 미완료다.
- 현재 발행본: **v0.1.50/code51**, [PR #95](https://github.com/al-hub/closepaw/pull/95), `fix/gemini-visible-controls-v0150`. 에이전트들은 전송 컨트롤/대기, 응답 범위·새 답변 보호, 진단·통합을 나누어 진행했다.
- 수정 전 [RED CI 39079668411](https://github.com/al-hub/closepaw/actions/runs/39079668411)에서 Android 2,347개 중 3개 실패로 visible-text Send, click 플래그, 가까운 클릭 부모 문제를 재현했다. PR/main CI·서명 probe·Signed Release 성공. Android 2,430개(86개 추가), 브리지 55+8개 통과. **실기기 설치·자동 왕복은 PENDING**이다.
- 발행본은 의미 기반 text/description+제한된 클릭 부모, 전송 준비 대기, 질문/응답 툴바 경계 수집, 전송 전 본문 지문 대조, 읽기 전용 역할 메타데이터를 포함한다. [수정·검증·인수 체크포인트](../dev/gemini-roundtrip-v0150.md).
- 발행·검증 완료: 소스/태그 `94db85162bb3ebca5bbbe7c9238a221ff58e8609`, APK 116,349,561 bytes, SHA-256 `04aee12642d101437ea047e8b8cd84a072308ea5b7d0b2f56a0670274360af91`. 공개 APK의 버전0.1.50/code51·기존 인증서·두 네이티브 런타임·체크섬을 확인했다. 이후 문서 커밋은 APK 소스와 구분한다.
- 다음 순서: [업데이트 APK](https://github.com/al-hub/closepaw/releases/download/v0.1.50/closepaw-aa-bridge.apk) 설치 → 실제 한 번 전송과 이번 응답 수집·같은 Voice 설명 검증. 실패/불확실 상태는 같은 task를 조회하며 자동 재전송하지 않는다.
- 기존 MCP 계약·페어링·터널·설정은 유지한다. 플러그인 재등록·데이터 초기화·재페어링은 요구하지 않는다. **P2 ACTIVE**, 자동 왕복 미완료를 유지한다.
- 아래 감사와 이전 발행 당시의 설치/READ 대기 문구는 역사 기록이다. 현재 상태는 이 절과 v0.1.50 체크포인트를 우선한다. 실제 질문·응답 원문과 위치 정보는 공개 문서에 남기지 않는다.

## 1. 시작 기준과 보존 조건

- 앱 기준 커밋: `bb7442a8c577669f53b79028e1d6d747c09210f2`.
- 마지막 실기기 설치 확인: **v0.1.47 / code 48**, Termux runtime **88.2**.
- 최신 공개 APK: **v0.1.48 / code 49**, source
  `209dc9e20767cdfd39ca59ff2c7f1936de539579`. **기기 업데이트와 Gemini 인수는 아직 대기**.
- [릴리스](https://github.com/al-hub/closepaw/releases/tag/v0.1.48),
  [Signed APK](https://github.com/al-hub/closepaw/releases/download/v0.1.48/closepaw-aa-bridge.apk).
- APK SHA-256: `9045eaa1afeeb065c3dc95310295ec523168dc8af59f31e74a0eefc04ed24bef`.
  크기 116,306,905 bytes. 배포 파일 버전/내용/체크섬을 검증했다.
- 서명 인증서 SHA-256:
  `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`.
  CI에서 기존 인증서와 일치 확인. 기존 키·태그·릴리스 자산은 변경하지 않는다.
- [v0.1.48 main CI](https://github.com/al-hub/closepaw/actions/runs/39023231120),
  [v0.1.48 Signed APK 빌드·발행](https://github.com/al-hub/closepaw/actions/runs/39023499964): 성공.
  이후 성공한 예약 실행은 발행 작업이 아니라 이미 있는 자산을 확인한 no-op일 수 있다.
- 이번 인계 커밋은 문서 전용이다. main이 앱 기준 SHA보다 앞서더라도 새 APK를
  뜻하지 않는다. 다음 Works에서는 먼저 최신 main을 확인한다.
- 페어링/토큰/터널/권한 설정/KUM 데이터를 유지한다. 토큰 내용·계정·개인
  네트워크 주소를 문서에 추가하지 않는다. 앱 삭제·데이터 초기화는 하지 않는다.

## 2. 전체 이슈·PR 상태와 증거 경계

| 대상 | 확인 상태 | 남은 작업 / 판정 |
| --- | --- | --- |
| P0 #67 | MCP Text/Voice 기준 DONE, 이슈는 추적용 OPEN | 과거 미체크 기본 템플릿보다 증거가 우선. 이슈 종료는 별도 관리 판단 |
| P1 #68 | DONE / CLOSED | Samsung Internet 한 화면 Voice 19초 증거 보존 |
| P2 #69 | ACTIVE / OPEN | Chrome Voice 및 Samsung 회귀 통과. Gemini 실제 응답 READ/Voice 미완료 |
| P3 #70 | LATER / OPEN | Kakao 범위 정하기 전 개발 시작하지 않음 |
| P4 #71 | 핵심 MCP 실행 실기기 통과 / OPEN | 취소·중단·중복·서비스 재생성 및 Voice 인수 결과는 별도 기록 |
| P5 #72 | PC 연결 부분 증거 / OPEN | SSH/WSL/KUM 정상은 세션 연속성·쓰기 복구 검증이 아님 |
| P6 #73 | LATER / OPEN | 제한된 READ만; 결제·송금·인증 범위 아님 |
| P7 #74 | 일부 실기기 통과 / OPEN | 아래 신뢰성 작업 묶음 참조; 전체 DONE 아님 |
| P8 #75 | LATER / OPEN | 반복 통합 근거 없이 전면 AppProfile 추상화 금지 |
| P9 #76 | LATER / OPEN | 두 번째 기기·이식성 인수 미완료 |
| #88 | 핵심 장애 복구·PATH·릴리스 인수 통과 / OPEN | 원래 소실 계기 미확정, 플랫폼 제약·확장 검증 분리; 아래 참조 |
| PR #33 | CLOSED / 미병합, superseded (2026-10-17) | 7파일 검토 완료; main 대체 경로와 폐기 근거를 PR 설명에 기록 |
| PR #82–87, #89–92 | MERGED | 병합은 해당 기능의 모든 실기기 검증 완료를 뜻하지 않음 |

단계 완료 수는 **P0/P1의 2/10**을 유지한다. P4 핵심 실행은 이미 검증되어
LATER/미구현으로 표시하면 틀리지만, 남은 인수 전체 통과로 확대하지 않는다.
P2의 과거 `read_app` enum 차단은 과거 클라이언트 현상이다. 현재 기존
operation envelope를 통해 새 Tailscale 작업을 발견·실행했다. Gemini의
새 작업도 먼저 현재 schema를 발견하고 판단한다.

## 3. 완료한 실기기 결과 — 재작업하지 않을 항목

증거 원문: [#88 통합 보고](https://github.com/al-hub/closepaw/issues/88#issuecomment-6204537136).

| 버전 | 실제 확인 | 한계 |
| --- | --- | --- |
| v0.1.45 / runtime88.2 | `command -v kv`, `kv status` exit 0; daemon SIGKILL 후 PID 교체 4.416초; token inode/size/mtime 유지; 중복 boot hook; 잘못된 토큰 HTTP401 구분 | 토큰 메타데이터 비교는 토큰 내용 공개가 아님; Android 전체 Termux force-stop과 다름 |
| v0.1.46 | 실제 reboot 후 unlock→사용자가 Tailscale 실행, 수동 Connect/Termux 없이 shell 복구; Stop→home→재열기에서 Stopped 유지; Connect 후 실행; Wi-Fi OFF→mobile 복구 | 무인 Tailscale 부팅·Stop→reboot·역방향 전환·복구 시간 SLA 아님 |
| v0.1.47 | 업데이트/권한 ON, 기존 연결에서 새 Tailscale operation 발견; 이미 reachable이면 connect 요청 없음 | MCP operation 확장 증거이지 새 MCP tool 자동 발견 증거가 아님 |
| v0.1.47 | 사용자 Tailscale OFF→VPN/target 장애 진단→ensure 1회→target reachable→별도 plain `kv status` exit 0 | VPN 전환 중 get_task 1회 timeout, 같은 task 재조회 성공; 연결 요청 재제출 없음 |

v0.1.47 작업 ID:

- 설치 직후 KV: `03dc9e58-59f8-4ecd-97f5-eddfaaafb2a6`.
- 연결 중 no-op: `481bf242-bf5b-400e-8797-86430599ec0f`.
- OFF 복구: `605be889-c9b3-4228-9e79-f028a5ea7836`.
- 복구 후 KV: `7c0dcc54-6558-439c-8bc3-cb72952baedd`, exit 0,
  Termux 작업 6,230ms, PC/SSH/WSL/KUM backend/자동 동기화 정상.

v0.1.47 신규 테스트 30개(로컬 Kotlin/통합27, Android 설정2, MCP 계약1),
Python Termux55+bridge core8, 전체 Android JVM 및 debug/signed 빌드 통과.
이는 새 미설치·로그아웃·충돌·전체 오프라인 실기기 테스트의 대체 증거가 아니다.

## 4. 작업 A 완료 — PR #33 미병합 종료

[PR #33](https://github.com/al-hub/closepaw/pull/33)은 사용자 승인 후
2026-10-17 18:40 KST에 **CLOSED / merged=false**로 종료했다.
main `6dcb384fae0c0b89f9874054b4559684001e30a7`과 7개 변경 파일을 비교했다.

| 파일 | 판정 | 근거 |
| --- | --- | --- |
| TermuxRunCommandExecutionAdapter.kt | 이미 반영 | capabilityAware 및 install probe gate |
| SessionToolingBootstrapper.kt | 이미 반영 | AndroidTermuxInstallProbe 연결 |
| TermuxRunCommandExecutionAdapterTest.kt | 이미 반영 | 미지원 시 서비스 미실행 회귀 테스트 |
| TermuxStatusMapper.kt | 현재 경로로 대체 | 초기 설정·인증 불일치·오프라인 복구 안내 |
| ToolsSection.kt | 현재 경로로 대체 | 수동 초기 페어링과 기존 토큰 유지 복구 UI |
| TermuxPlayBootstrap.kt | 구형 구현 폐기 | 토큰 포함/덮어쓰기, 직접 파일 교체, supervisor 없는 실행 |
| TermuxPlayBootstrapTest.kt | 구형 테스트 폐기 | 폐기한 설치 구조를 검증; base64는 토큰 비밀성 보장이 아님 |

필수 고유 동작은 남지 않았다. 한 줄 curl 설치 편의는 필요가 구체화될 때만
최신 main의 토큰 보존·atomic deploy·singleton supervisor·lifecycle/timeout·
불완전 설치 방어를 재사용하는 별도 작업으로 검토한다.
오래된 11커밋은 병합하지 않았으며 대체 PR은 필요하지 않다.
실행 코드·APK·기기 설정은 변경하지 않았고 오래된 CI도 재실행하지 않았다.
다음 순서는 B(Gemini 실제 인수)이며 P2는 ACTIVE를 유지한다.

## 5. 다음 신규 작업 B — Gemini 실제 인수 (#69, #86/#87 후속)

- v0.1.48/code49를 기존 앱 위에 업데이트 설치한 뒤 진행한다. 현재 마지막 설치
  확인은 v0.1.47이다. `discover_operations`로 `app.screen.read`,
  `gemini.draft.insert` 현재 schema/version/hash를 읽는다. 미지원이면 즉시
  이유를 기록하며 Chrome/웹 검색으로 대체하지 않는다.
- 사용자에게 완료된 Gemini 응답 한 화면만 준비하게 한 뒤 실제 READ와
  같은 대화의 Voice 3문장 요약을 비교한다. 모델 메뉴/입력창은 응답이 아니다.
- unlocked/empty composer에서 무해한 초안 1회 입력: confirmed task의
  verified=true, Send 미실행을 확인. 기존 draft 보존, local permission OFF,
  lock 및 Google Search 비대상 거부를 각각 확인한다. 쓰기 outcome_unknown이면
  재실행 전 실제 상태 확인; 자동 재전송 금지.
- `get_runtime_info`의 실제 client 노출·호출과 server tools/list=9를 구분한다.
  신규 Tailscale operation 성공으로 아홉 번째 tool 발견까지 통과시켜선 안 된다.
- `doc/dev/mcp-runtime-expansion-v0143.md`,
  `gemini-draft-expansion-acceptance.md`의 과거 후보/설치 지시를 현재 증거로 갱신.
- P2 완료는 실제 Gemini READ/Voice가 있을 때만. 초안 성공과 READ 성공은 별개다.

## 6. 다음 신규 작업 C — 신뢰성·작업 수명주기 (#71/#74/#88)

우선순위는 실제 사용 장애 또는 사용자 재현 요청에 따라 정한다. 구현이
필요한 결함과 Android 제약을 구분하며 모든 조합을 무작정 반복하지 않는다.

- service/process death 시 연결 의도 유지 및 daemon job 재관측, duplicate ID,
  취소 요청과 실제 종료 확인. write/unknown outcome을 자동 replay하지 않는다.
- mobile→Wi-Fi, 긴 완전 오프라인 후 복구, Stop→reboot에서도 paused 유지.
- reboot 후 Tailscale을 사용자가 열지 않은 조건에서 task-directed 복구 검증.
  unlock/인터넷/MCP 채널이 없는 동안 원격 도구 호출은 불가능하다.
- Tailscale missing/disabled/unsupported/로그인·VPN 동의/다른 VPN/활성 VPN 장애는
  기기 데이터 삭제 없이 가능한 항목만 테스트한다. Android의 VPN 소유자 비공개
  때문에 활성 VPN을 강제로 교체하지 않는 현재 정책을 유지한다.
- 최초 daemon 소실 계기는 증거 부족으로 미확정. 이미 수정한 recovery 결함과
  원래 Android 종료 원인을 혼동하지 않는다. 전체 Termux force-stop 후 복구는
  별도 외부 실행 채널이 필요하다.
- 정확한 복구 시간·Voice 연속성·장기 안정성은 측정 전 SLA로 주장하지 않는다.
- #88은 핵심 acceptance 충족 기록을 유지한다. 추가 결함이 없으면 핵심 종료와
  확장 reliability 후속 분리를 다음 작업에서 판단한다.

## 7. 관리·문서 잔여

- GitHub Projects #2의 실제 Status/Item closed 자동화는 별도 확인 필요.
  이 문서 커밋이나 이슈 수정으로 보드 동기화를 주장하지 않는다.
- P0 완료 이슈 #67은 OPEN 추적 상태다. 증거 보존 후 닫을지 관리 판단한다.
- 원격 업데이트/재시작 self-management는 PROGRESS의 계획 항목이다.
  get_diagnostics는 이미 존재하지만 updater/restart operation 전체가 있는 것으로
  확대하지 않는다. Android 설치 확인이 필요한 경계도 유지한다.
- 개발 가이드의 이 fork와 다른 옛 release.yml 설명을 현재 signed-release.yml로
  수정했다. 이번 문서 전용 commit은 `[skip ci]`로 불필요한 unchanged-version
  release 가드 실패를 피한다. 코드 변경에 skip을 복사하지 않는다.

## 8. 다른 Works에 복사할 시작 지침

```text
al-hub/closepaw 작업을 이어서 진행해줘.
최신 main의 AGENTS.md/CLAUDE.md, PROGRESS.md,
doc/project/WORKS_HANDOFF.md를 먼저 읽고 현재 GitHub 상태를 대조해줘.

작업 A(PR #33)는 7파일 검토 후 superseded로 미병합 종료했다.
PR 설명과 이 문서의 최종 판정을 보존하고 구형 11커밋을 병합하지 마.
다음은 B(Gemini 실제 READ/Voice·draft)와 C(잔여 신뢰성)를
현재 사용 가능한 도구·증거 기준으로 순서대로 이어가되 통과 항목을 반복하지 마.

공개 APK v0.1.48/code49, 마지막 설치 확인 v0.1.47/code48, runtime88.2. PATH 없는 kv status,
브리지 SIGKILL/토큰 유지, v46 reboot 및 Wi-Fi→mobile,
v47 Tailscale OFF→ClosePaw 복구→kv status는 기록된 실기기 통과다.
Gemini 및 다른 VPN/로그인/전체 오프라인/재부팅+Tailscale 조합은 미검증이다.
페어링·설정·KUM 데이터를 보존하고 플러그인을 재등록하지 마.

실행 코드 변경 시 failing regression→수정→관련/전체 CI→review/main→
버전 증가와 Signed APK 자동 릴리스→서명·자산 검증까지 진행해줘.
실제 폰 설치·필수 사용자 조작 단계만 요청하고 실기기 미검증을 완료 처리하지 마.
GitHub 문서·이슈·PR 상태를 함께 갱신하고 변경 파일/테스트/실기기/
APK 링크/미해결 제약을 보고해줘. 별도 Works 생성은 내가 할 테니
현재 Works에서 새 창을 만들었다고 주장하지 마.
```

## 9. 전체 제어 계획 — 당시 계획 기록, 현재 개발·발행 완료

사용자 요청으로 [Gemini 전체 동작 계획](https://github.com/al-hub/closepaw/blob/docs/gemini-roundtrip-plan-20261017/doc/project/GEMINI_ROUNDTRIP_PLAN.md)을 별도 브랜치에 저장했다.
대표 실기기 인수는 “Gemini를 통해 현재 위치 알려줘”다. 질문·전송·답변 수집·
Voice 보고의 왕복과 Gemini 위치 정보의 정확성/권한은 별도로 판정한다.
초안만 작성/현재 답변 읽기/명시한 추가 질문도 목표 실행·관측·후속 조치 흐름으로
정의했다. 질문 전송 기능은 v0.1.48에 없다.
서브 에이전트 3역할(입력·전송/답변 수집/통합 테스트)의 병렬 개발은 의견만 제시했으며
당시에는 시작하지 않았다. 이후 승인과 개발·발행 결과는 맨 위 최신 전달 상태를 참조한다. 배포 시간 최적화는 계속 보류한다.
계획 문서 작성을 추가 기능 개발 승인으로 해석하지 않는다.
