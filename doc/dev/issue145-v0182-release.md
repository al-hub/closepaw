# PR #155 — v0.1.82/code83 발행·설치 완료

2026-10-24 KST. PR HEAD `2b11ff9ffd0c9f9db626aa9b05e91f2e428d6b68`, 불변 main/APK/tag source `19382f9a1e7502d5db452dcd7c4ea610121826ea`.

## 변경과 검증

Gemini 상세 진행에 완료·현재·다음 필드를 추가했습니다. 계획과 관찰된 완료를 구별하고 전체 완료 미확정, 생성 취소 미확인, 자동 재전송 금지를 유지합니다. 모든 10개 stage, 실패/중단, 미지원 stage, 중복 이벤트, stale epoch 회귀를 포함합니다.

PR 최종 Security40086586708/UI40086587217, main Security40087024516/UI40087024566, Signed40087550035 모두 성공했습니다. PR/main 원시 XML과 acceptance report를 별도 다운로드해 main source/code 및 개별 case 정합을 검증했습니다: JVM **2737개**, 실패/skip0. PR/main UI **10개**, 실패/error/skip0, PNG4개입니다. UI suite 성공이 실제 Gemini 상세 패널의 전수 육안 수용을 의미하지는 않습니다.

버전 증가 전 HEAD8692a01의 성공은 최종 APK 증거와 구분했습니다. 첫 code83 후보000849f의 UI40086057482는 1/10 실패했습니다. 실패 artifact12557087087의 로그와 테스트를 보존·검토했습니다. awaitCondition은 true로 while을 빠져나온 뒤 condition을 즉시 재호출해, 창 재부착 중 false가 되면 5초 기한이 남아도 실패할 수 있었습니다. 기존 **5초 기한 안에서 50ms 간격 두 번 연속 true**를 요구하고 저장한 판정으로 assert하도록 fixture만 수정했습니다. 앱 보안/세션/타임아웃/반복 횟수는 완화하지 않았고, 알려진 실패를 지우거나 통과로 덮지 않았습니다. 실제 앱의 모든 순간 깜박임이 없다는 주장은 하지 않습니다.

## 공개 APK 독립 검증

[Release](https://github.com/al-hub/closepaw/releases/tag/v0.1.82) · [APK](https://github.com/al-hub/closepaw/releases/download/v0.1.82/closepaw-aa-bridge.apk)

- 116642415 bytes; SHA256 `dac1049eaa0e3284b932ed7a7f4fc270134f8971e27f43c716effd7929848086`.
- 익명 공개 다운로드와 별도 checksum 파일 일치.
- 기존 운영 인증서 `d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba` 유지.
- APK v3 RSA-SHA512 signed data 및 APK chunk-content digest를 직접 검증.
- 바이너리 manifest ai.closepaw/versionName0.1.82/versionCode83 직접 파싱.
- arm64 openaitunnel/cloudflared 포함, annotated tag의 source SHA 일치.
- 공개 검증 artifact12556849354와 원본 기록 대조. 새 키 생성/기존 태그 변경/중복 릴리즈 없음.

## 휴대폰 설치

설치 요청은 task `6c9e0c84-f093-4980-8c80-4f3983a50e53` **1회**입니다. get_task의 installing을 설치 완료로 오인하지 않고, durable self_update.status **installed/current0.1.82/code83** 후 독립 get_status **0.1.82/code83 reachable**을 확인했습니다. 제어 세션은 **ended/epoch0**, `2c59d492-3f60-4f6d-a19e-7c3d6f41cb46`입니다. 새 UI 세션 생성·물리 시험·설치 재전송은 없습니다.

## 이슈와 후속 경계

| 이슈 | 상태 |
|---|---|
| #145 | OPEN: 이번 상세 구조·자동 회귀 구현/배포 완료. 실제 Gemini 상세 패널의 완료/실패/취소 표시 수용은 NOT_TESTED. v0.1.81 잠금 해제 즉시 표시 사용자 PASS는 재사용 |
| #146 | CLOSED 유지: v0.1.81 회전·접근성 OFF/ON 후 동일 위치 사용자 확인과 좌표 증거, 기존 자동 회귀 재사용. 반복하지 않음 |
| #147 | OPEN: 실제 속도 개선/Voice E2E/엄격 통계 미입증. 이번 APK에 성능 개선 변경 없음. 기존 물리 X와 Y59 시험 반복 없음 |
| #150 | CLOSED 유지: 기존 자동 수용 체계 및 신규 exact-source CI evidence 사용 |

v0.1.81의 잠금 해제 즉시 표시: [기존 증거](https://github.com/al-hub/closepaw/issues/145#issuecomment-6382726940). #146 종료: [수용 대조](https://github.com/al-hub/closepaw/issues/146#issuecomment-6382801637). #117 장기/TalkBack/알림, #76 다른 기기/폴더블 분리 유지. 기존 PASS는 원 source/version의 REUSED_EVIDENCE이고 새 APK 물리 PASS로 승격하지 않습니다.

[구조화 원시 metadata](evidence/issue145-v0182-release.json)에 run/artifact/digest/설치 결과를 보존합니다. 이 보고서는 발행 후 docs-only 기록이며 APK source/tag를 바꾸지 않습니다.
