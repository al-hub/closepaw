# GitHub 저장소만으로 서명 APK 릴리스하기

> al-hub/closepaw의 실제 운영 경로. 2026-10-22 KST v0.1.76/code77 main-only R8·검증된 native cache·artifact 서명·공개 확인·휴대폰 설치로 검증.
> 다음 대화/Work는 이 문서와 현재 워크플로를 먼저 읽는다.

최신 [#135 성능 보고서](issue135-main-build-optimization.md): main→공개 검증593s,
최종 PR→공개1086s. 최초 native cache 생성 비용을 포함한 단일 표본이다.
일반 PR은 unsigned-release를 생략하고 main은 R8/두 런타임/기존 서명·공개 검증을 유지한다.
native cache는 정확한 source/toolchain/flags/script key만 사용하며 main의 unsigned job만
저장한다. 복원 검증 실패는 source build로 대체한다. `clean_gradle_cache=true` dispatch는
릴리스 경로도 시작할 수 있어 **새 미발행 버전의 CI에서만** 사용한다. 독립 cache maintenance로
오해하지 않는다. main 고정·기존 키·정확한 CI artifact·불변 태그 원칙은 동일하다.

**로컬 Android SDK나 서명키가 없어도 GitHub Actions에서 서명 APK를 만들고 공개 릴리스할 수 있다.**
기존 서명키는 이미 GitHub Actions secrets에 설정되어 있다. 저장소에 키 파일이
없거나 GitHub 커넥터로 secret 값을 읽지 못하는 것은 릴리스 불가 사유가 아니다.
사용자에게 키를 다시 달라고 하거나 새 운영 키를 생성하지 않는다.

## 1. 기준 파일과 진입점

| 목적 | 기준 파일 |
|---|---|
| 서명·태그·공개 APK 발행·독립 검증 | [signed-release.yml](../../.github/workflows/signed-release.yml) |
| main/PR 단위 테스트·lint·debug 빌드 | [security-ci.yml](../../.github/workflows/security-ci.yml) |
| GUI 변경의 에뮬레이터 계측 검증 | [premium-ux-ui.yml](../../.github/workflows/premium-ux-ui.yml) |
| 배포 버전 | [gradle.properties](../../gradle.properties)의 VERSION_NAME / VERSION_CODE |
| Android 서명 환경변수 연결 | [app/build.gradle.kts](../../app/build.gradle.kts) |
| 현재 완료/미완료 및 최신 소스 | [WORKS_HANDOFF.md](../project/WORKS_HANDOFF.md) |

문서와 코드가 다르면 현재 워크플로가 실행 동작의 기준이다. 검색이 0건이어도
위 경로를 직접 읽는다. 읽기 실패와 검색 결과 없음을 연결 장애로 혼동하지 않는다.

## 2. 보통은 PR 병합만으로 자동 실행된다

1. 최신 main, 릴리스, 관련 이슈와 개발 지침을 확인한다. 새 실행 코드에는 아직
   발행하지 않은 VERSION_NAME과 기존 설치본보다 큰 VERSION_CODE를 지정한다.
   이미 배포한 버전의 태그를 새 코드에 붙이지 않는다.
2. PR의 해당 HEAD에 대한 Security CI와 필요한 UI 테스트를 통과시킨 뒤 병합한다.
3. main의 **Security CI** 성공을 확인한다. PR CI 성공과 main CI 성공은 별개다.
4. 이 성공 이벤트가 **Signed Release**를 자동 시작한다. 수동 dispatch 없이도 된다.
5. Signed Release의 `signed-release` job이 최종 success가 될 때까지 확인한다.
6. `v<VERSION_NAME>` 릴리스에 다음 두 자산과 공개 다운로드 검증 결과가 있는지 확인한다.
   - `closepaw-aa-bridge.apk`
   - `closepaw-aa-bridge.apk.sha256`
7. 설치가 작업 범위에 포함되면 아래 설치 절차까지 수행한다. 물리 조작 검증은
   실제 실행한 항목만 별도로 기록한다.

**서명 빌드가 끝날 때까지 main에 후속 커밋을 넣지 않는다.** 워크플로는 빌드
소스가 현재 main인지 검증하며, 완료 후 만드는 태그는 그 APK 소스를 가리킨다.
완료 기록은 발행이 끝난 뒤 docs-only 커밋으로 추가한다.

## 3. 자동 시작이 없거나 커넥터에 dispatch 도구가 없을 때

먼저 현재 main SHA의 Actions 실행을 확인한다. 진행 중인 실행이 있으면 중복
실행하지 않는다. GitHub 커넥터가 workflow_dispatch를 지원하지 않아도 자동
릴리스 경로는 사용할 수 있다.

- `Signed Release`에는 UTC 매시 **:11 / :41**의 예약 fallback이 있다. GitHub의
  스케줄 지연은 가능하다. main 버전이 미발행이고 필요한 두 자산이 없으면
  Security CI를 dispatch하고, CI 성공이 다시 Signed Release를 시작한다.
- 이미 CI/릴리스가 실행 중이거나 두 자산이 있는 버전이면 자동 중복 실행을 피한다.
- 같은 main SHA에서 workflow_dispatch 방식의 Security CI(예약 fallback이 시작한
  실행도 포함)가 3회에 도달하면 자동 재시도를
  중단한다. 실패 원인을 조사한다. 반복 dispatch나 의미 없는 커밋으로 우회하지 않는다.
- gh CLI 또는 GitHub Actions UI를 사용할 수 있다면 main에서 직접 실행할 수도 있다.
  CLI는 별도 인증된 환경에서만 사용하며 토큰/키를 대화에 노출하지 않는다.

```bash
# main Security CI를 시작해야 할 때
gh workflow run security-ci.yml --ref main --repo al-hub/closepaw

# 실행 ID·소스 SHA·결과 확인
gh run list --workflow security-ci.yml --branch main --repo al-hub/closepaw
gh run list --workflow signed-release.yml --branch main --repo al-hub/closepaw

# 같은 현재 main SHA의 Security CI가 이미 성공한 뒤 수동 발행이 필요한 경우
gh workflow run signed-release.yml --ref main -f publish=true --repo al-hub/closepaw
```

GitHub UI에서는 Actions → Security CI 또는 Signed Release → Run workflow →
branch `main`을 선택한다. Signed Release의 `publish`는 true로 설정한다.
수동 Signed Release 역시 **현재 main의 성공한 Security CI**를 요구한다.
예약 실행의 `ensure-main-security-ci` success만으로 APK 발행 완료를 주장하지 않는다.

## 4. 기존 서명키와 빌드 내용

운영 워크플로는 다음 기존 GitHub Actions secrets를 사용한다. **이름만 문서화하며
값은 읽거나 출력하지 않는다.**

| Actions secret | 빌드에서의 사용 |
|---|---|
| CLOSEPAW_KEYSTORE_B64 | runner 임시 디렉터리에 기존 keystore 복원 |
| CLOSEPAW_KEYSTORE_PASSWORD | KEYSTORE_PASSWORD |
| CLOSEPAW_KEY_ALIAS | KEY_ALIAS |
| CLOSEPAW_KEY_PASSWORD | KEY_PASSWORD |

KEYSTORE_PATH는 runner의 임시 keystore 경로로 설정된다. 일반적인
`KEYSTORE_BASE64` 등의 다른 secret 이름을 추측해 별도 워크플로를 만들지 않는다.
기존 `issue-128-signed-apk.yml` 후보 경로는 삭제되었으며 운영 경로가 아니다.

워크플로는 기존 공개 v0.1.33 APK 인증서와 keystore를 먼저 비교한다. 현재 운영
인증서 SHA-256은 다음과 같다.

```text
d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba
```

Security CI의 `unsigned-release` job은 main에서만 실행한다. PR은 기존 테스트·lint·debug·UI 검증을 유지하고 optimized release/R8는 생략한다. 이 main-only 조건은 2026-10-22 사용자 요청으로 수정만 반영했으며 다음 개발 CI에서 검증한다.

main의 이 job이 두 Android 네이티브 런타임을 빌드·포함하고,
R8/리소스 축소를 유지한 `:app:assembleRelease`를 단위 테스트·lint/debug job과
독립 runner에서 병렬 실행한다. 모든 Security CI job 성공 후 Signed Release가
**동일 저장소/main/SHA/정확한 성공 CI run**의 unsigned APK만 내려받는다.
PR/fork/다른 workflow/다른 run 또는 버전·hash 불일치는 서명 전에 거부한다.
기존 키로 zipalign/apksigner 서명하고 이전 APK 인증서와 다시 비교한다.
단위 테스트는 같은 main CI에서 통과하며 서명 job에서 중복 실행하지 않는다.

태그/릴리스 및 인증 없는 공개 URL의 SHA256·서명·manifest·두 런타임·소스 검증은
유지한다. `public-apk-verification-<run_id>`가 독립 공개 검증 증거다.
테스트 보고서는 **Security CI**의 `android-test-reports-<run_id>`, task profile은
`security-gradle-timing-<run_id>` / `release-gradle-timing-<run_id>`에 있다.
unsigned artifact가 없거나 만료되면 현재 main의 Security CI를 원인 확인 후 재실행한다.
PR artifact나 다른 SHA로 대체하지 않는다. [#132 설계·계측·롤백](issue132-pipeline-performance.md).

로컬에서 서명 환경변수 없이 `assembleRelease`를 실행하면 unsigned 결과가
나올 수 있다. 로컬 SDK 다운로드 실패나 keystore 부재를 해결하려고 운영 키를
재생성하지 말고 위 GitHub 경로를 이용한다. 직접 서명하는 신뢰된 별도 호스트가
이미 있는 경우에만 기존 [로컬 빌드 안내](development.md#signed-release-builds-scriptsrelease-buildsh)를 따른다.

## 5. 실행 상태 확인과 실패 처리

- 사용할 수 있는 GitHub 도구를 먼저 확인한다. 커넥터에서는 Actions runs 조회,
  workflow run jobs 조회, 완료된 job logs 조회를 사용한다. 단순 GET fetch는
  workflow dispatch를 실행하지 않으며 APK 바이너리 다운로드도 지원하지 않는다.
- 실행 목록/상태/로그는 반드시 대상 SHA와 run/job ID를 대조한다. 이전 HEAD의
  success나 `queued`/`in_progress`를 현재 릴리스 성공으로 보고하지 않는다.
- Security CI의 `Build optimized unsigned release`는 배포용 최적화를 포함해 수 분 이상
  걸릴 수 있다. 진행 중 job의 로그 다운로드는 아직 404일 수 있다. job이 끝나기
  전에 이를 빌드 실패로 오인하거나 무작정 재실행하지 않는다.
- secret 누락 또는 인증서 불일치면 중단 원인을 기록한다. 새 키 생성, 인증서
  비교 제거, 다른 키로 재서명으로 우회하지 않는다. 기존 키 복구가 필요한 경우에만
  그 구체적인 사용자 조작을 요청한다.
- 현재 main과 빌드 SHA가 다르면 최신 변경을 먼저 확인한다. 이미 발행한 태그를
  이동하거나 APK를 덮어쓰지 않는다. 새 코드에는 새 버전으로 재검증한다.
- 실패 원인을 확인해 수정하거나 일시적 인프라 실패임을 확인한 뒤에만 해당
  실행을 재시도한다. 다른 대화창에서 이미 빌드/설치 중인 작업을 중복 제출하지 않는다.

## 6. 휴대폰 자동 업데이트와 완료 기준

설치가 사용자 요청 범위이고 ClosePaw 커넥터가 연결되어 있으면 다음 순서로 진행한다.

1. `get_status`로 실제 설치 버전을 확인한다.
2. `discover_operations`로 `app.self_update.check`, `.install`, `.status`의 현재
   입력 스키마·버전·hash·ready를 확인한다. 문서의 과거 hash를 복사하지 않는다.
3. `app.self_update.check`가 반환한 **정확한 available_version**을
   `.install`의 expected_version으로 전달하고 고유 request_id로 한 번만 제출한다.
4. `get_task`를 확인한다. accepted/queued 및 task success 안의 `installing`은
   실제 설치 완료가 아니다. APK 교체 중 연결이 잠시 끊길 수 있다.
5. 재연결 후 **먼저 `app.self_update.status`**를 읽어 durable installed/current의
   버전/code를 확인하고, 독립 `get_status`로 실행 중 앱 버전과 응답을 확인한다.
   결과가 불명확한 동안 install을 재전송하거나 MCP를 재등록하지 않는다.
6. Android/Samsung의 실제 설치 승인 대기가 관측되면 필요한 조작만 요청한다.
   키/권한 요청을 추측해서 선제적으로 반복하지 않는다.

서명 APK 발행, 설치, 연결 확인, 사용자 육안·물리 조작 검증은 각각 별개다.
실기기 드래그·진동·TalkBack 등을 실행하지 않았다면 미검증으로 기록하고 해당
수용 이슈를 유지한다. 이미 성공한 기존 기능은 새 회귀 위험이 없으면 반복하지 않는다.

## 7. 문서만 바꿀 때

실행 코드·빌드 설정이 바뀌지 않는 문서 수정은 버전 증가나 새 APK가 필요 없다.
필요하면 커밋 메시지에 `[skip ci]`를 붙여 같은 버전의 불필요한 릴리스 시도를
피한다. 실행 코드 변경에는 이 예외를 적용하지 않는다. 배포된 태그는 그대로 둔다.

## 8. 실제 성공 사례와 다음 작업의 기록

- PR #131 → 불변 APK 소스 `c124f3f86994664c9685b883cb31e1796b38c894`.
- main [Security CI39608950694](https://github.com/al-hub/closepaw/actions/runs/39608950694),
  [Premium UX UI39608950568](https://github.com/al-hub/closepaw/actions/runs/39608950568),
  [Signed Release39610112952](https://github.com/al-hub/closepaw/actions/runs/39610112952): success.
- [v0.1.74/code75](https://github.com/al-hub/closepaw/releases/tag/v0.1.74)의 APK와
  SHA256 자산 공개, 동일 서명 검증 후 휴대폰 installed/current0.1.74/code75 및
  재연결 확인. [상세 실행 근거](issue128-release-v0174.md).

다음 작업도 PR·소스 SHA, 버전/code, CI·Signed Release run 링크, APK·checksum,
설치 task와 독립 버전 확인, 실행하지 못한 검증을 이슈와
[WORKS_HANDOFF.md](../project/WORKS_HANDOFF.md)에 남긴다. 과거 성공 버전을
다음 릴리스 버전으로 그대로 재사용하지 않는다.

## 9. 병렬 CI 산출물 서명 성공 사례 (#132)

PR#133 → source/tag `04c849b701c0d6a4c8dd332053619a7135db2584`,0.1.75/code76.
main Security39617725426/UI39617725429 → Signed39619258531 success,
공개 검증 및 단일 요청 설치 후 durable installed/current와 독립 get_status 확인.
main→공개1095s→653s(40.4% 단축), runner 총 사용량은 별도 비교한다.
[전체 근거·제약·롤백](issue132-pipeline-performance.md). 기존0.1.74 성공 사례는
과거 경로의 증거로 보존한다. workflow artifact가 만료되면 현재 main의 새
Security CI dispatch로 산출물을 만든다. 성공한 unsigned artifact가 남아 있는
run 전체를 재실행하면 동일 이름 upload가 충돌할 수 있으므로 원인에 맞는
실패 job만 재시도하거나 새 CI dispatch를 사용한다. 서명 중 main을 변경하지 않는다.

## 10. 변경만 반영한 운영 조정 — 2026-10-22 (#134)

사용자가 지침과 workflow 파일 수정까지만 요청했다. unsigned-release를 main으로 제한하고 일반 PR의 추가 R8 실행을 제거하도록 설정했다. 이번 커밋은 [skip ci]로 저장하며 VERSION_NAME/CODE·기존 APK·태그는 그대로다. 변경된 조건의 CI·서명·새 APK·설치는 실행하지 않았다. 이전 v0.1.75 성공이 이 조건의 검증 증거는 아니다.

다음 실제 개발 작업에서 새 버전/code와 함께 PR의 security-build-test/UI success 및 unsigned-release skipped, main의 두 Security job success, 기존 키 서명·공개 APK 검증을 확인한다. 설치가 요청 범위면 한 번 제출 후 약60초 재연결 여유를 두고 durable status와 독립 앱 버전을 확인한다. 상세 조회 간격과 일괄 제출 원칙은 [AI.md](../../AI.md)를 따른다. #134는 검증 대기로 유지한다.
