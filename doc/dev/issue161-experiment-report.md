# #161 계측·제한 검증 Worker 선행 실험

## 현재 판정: PARTIAL, #161/#147 OPEN

작업 기준 2026-10-25. 감사 main `34b602a469ef3325bf13c73517c4338c7b224adf`,
기존 설치/릴리스 v0.1.85/code86, APK source `8ed27ad6bb9cc97a6d28a21d8a560ed2ab150088`.
이 문서는 준비·모델 시험과 실제 Android 실측을 구분한다. PR의 정확한 source SHA/CI/리뷰 결과는
#161 댓글에 연결한다. 본 변경은 production 계측을 포함하므로 test-only 변경이 아니다.
현행 버전 그대로 main 병합·릴리스하지 않는다. 검증한 PR로 보존하고, 실제 배포가 필요한 다음
단계에서 미발행 버전/code와 운영 서명 실행서를 적용한다.

## 감사와 변경

AI.md, AGENTS.md, WORKS_HANDOFF, 안전 최적화 검토, acceptance README, signed-release 실행서,
#147 본문/최신 댓글 및 #161 본문/댓글과 관련 코드를 확인했다.
GenericControl은 single-use 관찰·shared certifiedWindows/actionChangeVersion/chain state를 사용한다.
따라서 action()이나 capture() 자체를 병렬 호출하지 않는다. 기존 full fresh guard, 400ms throttle,
메인 스레드 native boundary, epoch/사용자 우선권, screenshot buffer 해제는 보존한다.

1. `ControlTiming`: invocation-local bounded flat events(최대2048, 누락수 공개), thread-safe 기록,
   고정 event/phase vocabulary, generated ID와 command step. 프로덕션 clock은
   `SystemClock.elapsedRealtimeNanos`이며 시작점 상대 ns만 기록한다. 원 nanoTime nested span과
   차감하지 않는다. `buffer_complete`는 용량 손실 없음만 뜻하며 callback 완료를 뜻하지 않는다.
2. sequence 진단의 `android_timing`에서 probe queued/slot ready/Main entered/실제 API dispatch/
   callback/cancel을 분리한다. gesture callback과 실제 sequence ACTION_CLICK dispatch/return도 구분한다.
   guarded snapshot 획득은 실제 픽셀 렌더 시각이 아니다. CANCEL_OBSERVED는 예외 관찰 시각이다.
3. 기존 단계의 flat start/end는 native/cue/traversal/post/result 등 inclusive 시간이다.
   모든 stage를 합산하면 중복 계산된다. 프로브 시도마다 새 ID, step마다 같은 invocation correlation을
   쓴다. OS window ID/패키지/화면 원문/예외 메시지는 새 계측에 포함하지 않는다.
4. `scripts/control_timing_report.py`: clock/누락/역전/중복을 거부하고 실제 dispatch gap과
   API callback 지연을 구한다. 없는 callback은 NOT_OBSERVED, 별도 실제 속도 개선율은 계산하지 않는다.
5. test-only `VerificationPipelinePrototype`: 기존 서비스에 연결하지 않은 coroutine 실험.
   execute+fresh immutable snapshot은 직렬, 이후 순수 검증 Worker만 중첩한다. semaphore가
   실행·대기·검증중 전체 미확인 합계를1/2로 제한한다. 명령 ID/epoch/generation/기대 숫자/실제 숫자/
   freshness를 검사한다. 이벤트만으로 성공을 인정하지 않는다. 위험/비가역 동작 API는 없다.
   단일 owner dispatcher 가정이며 Android native-boundary security equivalence를 입증한 것은 아니다.
6. 중단은 남은 채널 폐기·양쪽 worker 취소·미확인 명령 OUTCOME_UNKNOWN으로 전환한다.
   재시작/replay 금지, last_verified는 연속 prefix다. 검증실패 후에도 직전 실행이 이미 있었을 수 있다.

## 시험 및 재현

```bash
python3 -m unittest discover -s scripts/tests -v
./gradlew :app:testDebugUnitTest --tests 'ai.closepaw.chatgpt.control.ControlTimingTest' \
  --tests 'ai.closepaw.chatgpt.control.VerificationPipelinePrototypeTest'
python3 scripts/control_timing_report.py trace-with-android-timing.json --output build/issue161/timing.json
```

로컬 Python42개 PASS. 로컬 JVM 실행은 Gradle 배포 다운로드의 `Network is unreachable`로 BLOCKED;
SDK도 없다. 같은 실패를 재시도하지 않고 PR Security CI/full JVM/lint/debug 및 기존 UI gate를 사용한다.
Kotlin 시험의 실제 성공 여부는 정확한 PR HEAD의 CI 결과를 확인한 뒤 #161에 기록한다.

새 Kotlin 시험12개: collector4개, 시제품8개. cap1/2·순서/backpressure·검증 지연, 앱전환/잠금/권한회수/
X/사용자직접조작/세션종료, 이벤트만/누락 결과/중복·역순 callback/오래된 snapshot/다른 generation·epoch/
잘못된 결과, immutable snapshot, 외부 취소 및 각 worker 자체 CancellationException/일반 예외,
종료후 재실행금지를 주입한다. 이것은 테스트더블의 안전성 시험이고 실제 Galaxy/X 시험이 아니다.
늦은 screenshot callback의 buffer 해제는 기존 production 코드를 유지하되 신규 실기기 시험은 하지 않았다.

`app/build/reports/benchmarks/issue161-virtual-model.json`은 Kotlin testScheduler의 가상시계10+10회다.
명령당1200ms·검증40ms를 임의 모델 입력으로 사용한다. 이 값은 Android 측정값/예측/시간 하한이
아니며 실제 benchmark로 acceptance.py에 제출하면 안 된다. CI acceptance artifact에 보관한다.
raw XML과 해당 model JSON을 exact-SHA CI artifacts로 보존한다.

## 기록 방식 비교: 계약 분석, 비용 실측 NOT_TESTED

| 방식 | 결과 증명력/경쟁 조건 | 다음 판단 |
| --- | --- | --- |
| 접근성 이벤트 | wakeup이며 성공 증명 아님; 누락/중복/지연 가능 | 단독 성공 판정 제외, 전달지연 실제 측정 필요 |
| guarded immutable snapshot | 다음 조작 전에 명령·epoch·generation과 후조건을 결합해야 함 | 순수 후조건 분석 중첩의 우선 후보; 획득은 직렬 유지 |
| 최소 screenshot | 캡처 시점이 다음 명령과 섞일 수 있음; 이미지 자체만으로 원 명령 입증 불가 | 필요할 때만 사용; 실제 획득/CPU/메모리 비용 측정 필요 |

취득시간/이벤트 누락률/중복률/CPU/메모리 우열은 측정하지 않았다. 위 선택은 계약에 따른 설계 권고다.

## 성능 결과 및 목표

| 증거 | 기준 | 후보 | 개선 배율 |
| --- | --- | --- | --- |
| 기존 code86 task smoke | 3회 평균11,310.3ms, 정확3/3 | 없음 | 산출 불가 |
| 새 Android 동일조건10+10 | NOT_TESTED | NOT_TESTED | NOT_TESTED |
| 새 coroutine 가상시계10+10 | 1200+40ms의 임의 입력 모델 | in-flight2의 같은 모델 | 실제 개선율로 사용 금지 |
| Voice E2E / CPU / 메모리 / X 반응시간 | NOT_TESTED | NOT_TESTED | 산출 불가 |

code86의11.310초는 서버 task started/completed 범위이며 Android 내부 단조시계 trace와 동일시하지 않는다.
[원래 smoke 댓글](https://github.com/al-hub/closepaw/issues/147#issuecomment-6392816297)의 원 source/범위를
유지한다. 과거 code81 raw139span/28probes/6.439s spacing 역시 REUSED_EVIDENCE다.

28회 slot을 같은 throttle에서 직렬·reset 없이 유지할 때 첫~마지막 slot admission 정책 산술은
27×400=10,800ms다. 이는 실제 API dispatch 간격/신규 실측/기기 입력시간 하한이 아니다.
Main 진입 전에 slot시각을 갱신하므로 API 간격은 새 dispatch 계측으로 별도 확인해야 한다.
이 호출 구조를 그대로 둔 verifier 중첩만으로3초/1.1초에 도달할 수 없다는 구조적 제약이다.
새로운 보안 동등 guard/호출 구조를 입증하기 전 검사 제거·캐시·간격축소를 하지 않는다.

## 독립 리뷰

별도 리뷰어가 shared-state 병렬 action 금지/flat trace/전체 in-flight 제한/연속 prefix를 확인했다.
발견한 child 자체취소시 교착 위험은 stop(cancelled)+sibling 취소 및 회귀시험으로 수정했다.
complete 의미와 취소 시각 오표시는 buffer_complete/CANCEL_OBSERVED로 수정했다.
계산기 click 경로와 gesture 경로를 혼동하지 않도록 native click 직접 계측을 추가했다.
독립 리뷰어가 세 수정과 새 click 계측을 재확인했고 선언된 실험 범위에서 추가 blocking 결함은 발견하지 못했다. CI 증거는 이슈 댓글에 추가한다. 이 검토는 Android 보안 동등성 실증이 아니다.

## 완료 판정과 다음 최소 작업

| 필수 항목 | 상태 |
| --- | --- |
| 사전 감사 | 완료 |
| 세부 계측 코드 | 구현, CI/배포/실측 구분; render/event/실제 취소요청 경로 미계측 |
| 기초 성능 A~F | NOT_TESTED, 실제 Android 실험 필요 |
| 기록 방식 비교 | 계약 분석만 완료, 비용 실측 NOT_TESTED |
| 제한 병렬 시제품·오류 주입 | 테스트더블 구현; exact-SHA CI 확인 필요 |
| X·실기기 안전성 | 원래 PASS는 REUSED_EVIDENCE; 변경 공통경로 신규 시험 NOT_TESTED |
| 동일조건 실제 성능10+10 | NOT_TESTED |
| 독립 리뷰 | 수행·지적 수정·재검토 완료 |
| CI | PR exact-SHA 결과로 갱신 |
| 결과 기록·#147 인계 | PR 및 이슈 댓글로 연결 |

1. CI와 리뷰를 확인한 계측 PR을 보존한다. 현 단계에서 운영 APK/설치/세션 변경은 필요 없다.
2. 실제 계측 단계에는 현재 main과 다른 작업의 버전 충돌을 먼저 확인하고 미발행 version/code로
   계측 APK를 빌드·기존키 서명·공개검증한다. 사용자 휴대폰에 설치할 경우 설치범위와 상태를 확인한다.
3. 종료된 세션을 자동 재개하지 않는다. 사용자의 새 명시적 기기실험 요청 후 세션 상태/ID를
   확인하고 동일 workload/초기화/OS·설정/clock 범위로 실험 A~F를 수집한다.
4. production action은 병렬화하지 않는다. guarded 증거 이후의 순수 검증만 중첩할 실제 비용이
   확인되면 그 경로를 통합하고10+10 및 변경경로 취소/안전성을 수용한다. 무효 가설이면 부정적 결과를 기록한다.

필수 실제 실험이 남아 있으므로 #161 종료 불가. #147도 OPEN 유지.
