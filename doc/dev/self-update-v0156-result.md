# ClosePaw 자체 업데이트 검증 기준 — v0.1.56

검증일: 2026-10-18 (한국 시간). 다음 업데이트·장애 분석에서 재사용할 사실 기록입니다.

## 1. 이번에 확실히 확인한 결과

- Galaxy에서 **v0.1.55 / versionCode 56 → v0.1.56 / versionCode 57** 원격 업데이트를 실행했습니다.
- [PR #101](https://github.com/al-hub/closepaw/pull/101)의 최종 변경은 `gradle.properties`의 버전 이름·코드 두 줄뿐입니다. 진단·콜백 코드 등 제안했던 추가 기능은 최종 배포에서 모두 제외했습니다.
- 원격 자체 업데이트 경로는 v0.1.55에 이미 구현돼 있었습니다. v0.1.56은 경고 제거를 위한 새 수정이 아니라, 기존 경로를 실제로 시험할 상위 버전입니다.
- PR 검증·main 검증·서명 릴리스가 성공했습니다. 서명 릴리스의 기존 인증서 일치 검사도 통과했습니다.
- 기기에서 최신 릴리스를 조회해 `available_version=0.1.56`을 확인하고, 그 버전으로 설치 요청을 **한 번만** 보냈습니다.
- 작업 결과가 처음 `installing`을 반환했을 때는 기존 버전이 실행 중이므로 설치 성공으로 간주하지 않았습니다.
- 다음 조회에서 연결 시간 초과가 한 번 발생했습니다. 설치를 재요청하지 않고 연결 회복을 기다렸습니다.
- 이후 `get_status`가 **v0.1.56 / code57에서 응답**했습니다.
- 이어 `app.self_update.status`에서 **state=installed**, 목표·현재 버전 **0.1.56**, 목표·현재 코드 **57**이 일치했습니다.
- 원격 업데이트 허용과 ClosePaw의 APK 설치 권한이 업데이트 후에도 유지됐습니다.
- 이번 시도에서 승인 대기 결과를 관찰하지 않았고, ChatGPT가 추가 사용자 승인이나 수동 설치를 요청하지 않았습니다.

**확정된 성공 범위: 원격 명령으로 업데이트 시작 → 목표 버전 설치 확인 → 업데이트 후 ClosePaw 연결·응답 확인.**

## 2. 코드로 확인한 설치 경로 차이

| 항목 | 기존 수동 업데이트 | 이번 원격 자체 업데이트 |
|---|---|---|
| 호출 경로 | AppUpdater.requestInstall | AndroidSelfUpdater.start |
| 설치 요청 | FileProvider의 APK URI를 ACTION_VIEW로 설치 화면에 전달 | PackageInstaller 세션 생성·APK 기록·commit |
| 사용자 확인 관련 요청 | APK 열기 Intent 사용 | USER_ACTION_NOT_REQUIRED 요청 |
| 관련 권한·설정 | APK 설치 출처 허용 확인 | 같은 설치 권한 + 로컬 원격 업데이트 허용 확인; UPDATE_PACKAGES_WITHOUT_USER_ACTION 선언 |
| 이번 관찰 | 이전 v0.1.54→v0.1.55 수동 설치 캡처에서 출처 경고 확인 | v0.1.55→v0.1.56 원격 설치 완료 및 재연결 확인 |

소스 기준:
- [v0.1.55 AppUpdater.kt](https://github.com/al-hub/closepaw/blob/v0.1.55/app/src/main/kotlin/ai/closepaw/update/AppUpdater.kt)
- [v0.1.55 AndroidSelfUpdater.kt](https://github.com/al-hub/closepaw/blob/v0.1.55/app/src/main/kotlin/ai/closepaw/update/AndroidSelfUpdater.kt)
- [v0.1.55 AndroidManifest.xml](https://github.com/al-hub/closepaw/blob/v0.1.55/app/src/main/AndroidManifest.xml)
- [v0.1.56 소스 커밋](https://github.com/al-hub/closepaw/commit/0107e0d953f837c97fea039a96cd48fbf2f8dda2)

정확한 구분은 **APK 열기 Intent와 직접 설치 세션의 차이**입니다. 수동 경로도 내부적으로 안드로이드 설치 시스템을 사용하므로 “PackageInstaller를 쓰느냐 전혀 안 쓰느냐”로 단순화하지 않습니다.

## 3. 캡처에서 확인한 경고와 미확인 사항

수동 설치 캡처의 제목은 **“앱 설치가 권장되지 않음”**, 본문은 **“인증되지 않은 출처의 앱입니다.”**이며, 버튼은 **“무시하고 설치” / “설치 안 함”**입니다. 본문은 Play 스토어·갤럭시 스토어 같은 인증된 스토어 사용을 안내합니다. 캡처에는 서명 불일치나 악성코드 탐지라는 문구가 없습니다.

다음 내용은 **확정하지 않았습니다**.

- 해당 경고를 실제로 표시한 시스템 구성요소와 내부 발생 조건.
- 설치 경로 차이만이 경고 유무를 결정한 유일한 원인인지 여부. 설치 당시 실행 버전·설정·대상 버전도 달라 엄격한 동일 조건 비교 시험은 아닙니다.
- 모든 수동 업데이트에서 같은 경고가 반드시 발생하는지 여부.
- 모든 향후 원격 업데이트에서 승인 없이 설치되는지 여부.
- 이번 원격 설치 중 화면에 경고가 단 한 번도 표시되지 않았는지 여부. 별도 화면·사용자 조작 기록이 없으므로, **“추가 승인 대기가 관찰되지 않았고 원격 설치가 완료됐다”**로 표현합니다.

이번에도 APK 배포처는 GitHub였습니다. 인증된 스토어 배포로 변경하거나 보안 설정을 끄거나, 자동화로 “무시하고 설치”를 누른 사실은 없습니다. **기존 수동 경고의 근본 원인을 제거했다고 기록하지 않습니다.**

## 4. 재현·릴리스 근거

- [PR Security CI](https://github.com/al-hub/closepaw/actions/runs/39122847299): 성공.
- [main Security CI](https://github.com/al-hub/closepaw/actions/runs/39123153623): 성공.
- [Signed Release](https://github.com/al-hub/closepaw/actions/runs/39123502932): 성공; 기존 서명 인증서 일치 검사·공개 포함.
- [v0.1.56 릴리스](https://github.com/al-hub/closepaw/releases/tag/v0.1.56): APK와 체크섬 파일 공개.
- 공개 APK 크기: **116,397,169 bytes**.
- GitHub APK asset SHA-256: `bc77f123750939ed43e6af9fa300406d5dc340e4b24118991cbf88c18cfe2b97`.
- 이번 작업 환경에서 공개 바이너리를 내려받아 독립 재검증한 것은 아닙니다. CI의 서명 검사 결과와, 실제 설치 전 기기 코드의 체크섬·패키지·버전·서명 검증 경로를 근거로 구분합니다.
- 터널 주소·세션 식별자·페어링 정보 등 기기 접속 정보는 이 공개 기록에 넣지 않습니다.

## 5. 다음 업데이트와 장애 분석 원칙

1. 이 기록을 먼저 확인하고, 현재 버전·연결·설치 권한과 최신 릴리스를 조회합니다.
2. 검증된 최신 버전을 expected_version으로 지정해 설치를 한 번 요청합니다.
3. queued/accepted/installing은 설치 완료가 아닙니다.
4. 교체 중 연결이 끊기면 설치를 반복하지 말고 재연결 후 버전·코드·상태를 조회합니다.
5. 목표 버전·코드 설치와 새 버전의 응답을 모두 확인한 뒤 성공을 보고합니다.
6. 승인 대기·설치 실패·연결 미복구를 구분합니다. 승인 대기만으로 캡처와 같은 출처 경고라고 단정하지 않습니다.
7. 문제가 생기면 이번 성공 사례와 달라진 설치 경로·실행 버전·권한·서명·상태 전이를 먼저 비교하고, 필요한 증거만 추가 수집합니다.
8. 보안 승인이 실제로 필요하면 기존 시스템 승인 흐름을 안내합니다. 무조건 재설치하거나 보안 설정을 자동 해제하지 않습니다.

공식 API: [PackageInstaller.SessionParams.setRequireUserAction](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)).
