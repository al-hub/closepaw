# #161 v0.1.86 계측 APK 배포·실기기 준비

2026-10-25 KST. **기존 안전 실행 경로의 상세 병목 계측 준비 READY. #161 전체는 PARTIAL/OPEN.**

## 완료한 준비

- [PR#162](https://github.com/al-hub/closepaw/pull/162) 병합, APK/tag source `e636350bdfec5ca87d683b907d67b268582556a6`, v0.1.86/code87.
- PR [Security40164745723](https://github.com/al-hub/closepaw/actions/runs/40164745723)/[UI40164745734](https://github.com/al-hub/closepaw/actions/runs/40164745734) success. raw ZIP SHA256 대조·XML 독립 집계: JVM2765/UI11, 실패·오류·skip0, PNG8. Python43 PASS.
- 병합 직후 push CI가 시작되지 않아 기존 GitHub Actions 화면에서 main Security CI를 1회 실행했다(캐시 삭제 미선택). [main Security40165238220](https://github.com/al-hub/closepaw/actions/runs/40165238220)의 security-build-test/unsigned-release success. main raw XML JVM2765 실패·오류·skip0.
- [Signed40165607282](https://github.com/al-hub/closepaw/actions/runs/40165607282) success. 정확한 main CI unsigned artifact, 기존 운영키, 새 불변 태그, 익명 공개 다운로드·SHA256·v3서명·manifest·두 runtime·tag/source 검증 모두 success. public verification ZIP을 내려받아 digest 및 원시 검증내용 확인.
- 공개 [v0.1.86](https://github.com/al-hub/closepaw/releases/tag/v0.1.86), APK116650607bytes, SHA256 `69bb0470cca09672be550c6e6ed0af5cdb71a546869ac8bffc7ff88295916fcc`. 기존 signer `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`.
- 설치 요청 단1회 task `9a8f535b-6e50-4756-b13e-549dcf7682ab`. task의 installing을 완료로 오인하지 않고 후속 durable installed/current0.1.86/code87 → 독립 get_status reachable0.1.86/code87 확인.
- 새 버전 get_diagnostics 정상, generic_control.sequence_traces export 구조 확인. fresh discovery android.ui.sequence ready=true, 현재 입력계약 확인. 플러그인 재등록/설치재전송0.
- 읽기 전용 session.status는 ended/epoch0. 새로운 UI세션·계산기입력·화면읽기·물리X요청0. 종료상태 유지.

## 다음 실제 측정 절차

1. 사용자의 현재 명시적 계산기 실험 요청을 받으면 fresh session.status를 확인한다. 허용된 새 active session의 정확한 session_id를 쓴다. ended를 과거 요청으로 재개하지 않는다. X/사용자우선권이 관찰되면 중단하고 자동 replay하지 않는다.
2. 앱 카탈로그/관찰로 실제 계산기를 확인하고 기존 안전 경로로 초기값을 비운다. 초기화와 1~9 측정 구간을 분리한다. 먼저 최소 smoke(기존 지침 최대3회 이내)로 정상 수집을 확인한다.
3. fresh discover android.ui.sequence로 labels1~9와 session_id를 제출한다. get_task로 완료/불확실성을 확인하고 최종 표시123456789를 별도 관찰한다. guarded click success만으로 목표정확도 PASS를 주지 않는다.
4. **각 실행 직후** get_diagnostics의 generic_control.sequence_traces에서 해당 task/correlation의 android_timing을 회수한다. 순환버퍼는4개 trace만 보관하므로10회 후 한 번에 회수하지 않는다. 누락·truncated·buffer_complete=false이면 해당 성능판정 불가로 기록한다.
5. 추출한 단일 trace를 `python3 scripts/control_timing_report.py trace.json --output timing.json`으로 분석한다. 새 clock은 Android elapsedRealtime relative ns, 기존 nanoTime span/서버task 시간과 차감하지 않는다. inclusive stage 전체를 더하지 않는다.
6. device/OS/설정/network/workload/초기값/source/버전/실행경로/clock 조건을 기록한다. 오류/실패 샘플도 보존한다. 기존11.310초는 역사적 참고이며 새 clock 성능배율에 직접 쓰지 않는다.
7. 실험 후 자신이 시작한 세션만 정상 종료하고 확인한다. 사용자가 이미 종료했다면 재시작/중복종료하지 않는다.

## 준비 범위와 미완료를 구분

| 항목 | 상태 |
| --- | --- |
| 기존 실행경로 보안probe/Main/API/callback/native 단계 계측 APK·설치·연결 | READY |
| 진단 로그 export 및 호스트 분석기 | READY; 신규 실제 trace는 아직 없음 |
| 병렬 검증 Worker | src/test 전용; 실제 Android operation 미통합 |
| A~F 전체/이벤트전달·실제픽셀렌더/CPU·메모리 | NOT_TESTED; 일부 추가하네스 필요 |
| 실제 baseline/병렬candidate 각10회 비교 | NOT_TESTED; 후보통합 전 불가 |
| 변경경로의 실제 X반응시간·보안동등성 | NOT_TESTED |
| 실제 개선시간·배율/3초·1.1초 | 미측정 |

즉, 새 배포는 성능 개선 완료가 아니라 **상세 계측을 시작할 수 있는 준비**다. 비용과 안전계약을 실측한 뒤 병렬 후보 통합 여부를 결정한다. 현행28probe/400ms full fresh guard를 유지한다. #161/#147을 닫지 않는다.

[기계판독 근거](evidence/issue161/device-preparation-v0186.json) · [기존 실험보고서](issue161-experiment-report.md)
