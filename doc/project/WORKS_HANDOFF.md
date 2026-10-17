# ClosePaw GitHub 정합성 감사 및 Works 인계

기준: **2026-10-17 KST**, `al-hub/closepaw`의 main, 열린 이슈/PR 전체,
최근 병합 PR, Actions, 릴리스, 진행표 및 운영·인수 문서를 대조했다.
GitHub Projects의 실제 필드·자동화 설정은 이번 감사에서 읽거나 변경하지 않았다.

## 1. 시작 기준과 보존 조건

- 앱 기준 커밋: `bb7442a8c577669f53b79028e1d6d747c09210f2`.
- 최신 공개·설치 APK: **v0.1.47 / code 48**, Termux runtime **88.2**.
- [릴리스](https://github.com/al-hub/closepaw/releases/tag/v0.1.47),
  [Signed APK](https://github.com/al-hub/closepaw/releases/download/v0.1.47/closepaw-aa-bridge.apk).
- APK SHA-256: `22251254b912a4798e2677277787b9de45bafa9981d86a913a80644fbd624bb4`.
  크기 116,303,181 bytes. 배포 파일 버전/내용/체크섬을 검증했다.
- 서명 인증서 SHA-256:
  `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba`.
  CI에서 기존 인증서와 일치 확인. 기존 키·태그·릴리스 자산은 변경하지 않는다.
- [main CI](https://github.com/al-hub/closepaw/actions/runs/39002227928),
  [실제 Signed APK 빌드·발행](https://github.com/al-hub/closepaw/actions/runs/39002578644): 성공.
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

- 최신 설치 v0.1.47 유지. `discover_operations`로 `app.screen.read`,
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

기준 APK v0.1.47/code48, runtime88.2. PATH 없는 kv status,
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
