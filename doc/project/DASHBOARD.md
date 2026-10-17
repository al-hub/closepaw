# ClosePaw · Project Dashboard

> 기준 **2026-10-17 KST**. [PROGRESS.md](../../PROGRESS.md)가 milestone 기준이며,
> [Works 인계/감사](WORKS_HANDOFF.md)에 전체 이슈·PR·증거·잔여 작업을 기록한다.
> GitHub Projects의 실제 Status/자동화와 이 문서는 별도다.

| 영역 | 확인 상태 | 다음 게이트 |
| --- | --- | --- |
| 전체 | P0/P1 **2/10 DONE**, P2 ACTIVE | Gemini native READ/Voice |
| P0 / P1 | MCP Text/Voice 및 Samsung 화면 Voice 통과 | 검증 기준 보존 |
| P2 #69 | Chrome Voice, Samsung 회귀 통과 | Gemini READ/Voice 및 draft 인수는 각각 확인 |
| P4 #71 | MCP Termux 실행·경로 없는 kv status 실기기 통과 | 작업 수명주기/Voice 별도 인수 |
| P5 #72 | PC/SSH/WSL/KUM 연결 정상 | 세션 연속성은 별도 |
| P7 #74 | v46 reboot, Stop→재열기, Wi-Fi→mobile 증거 | 역방향/장기 오프라인/Stop→reboot/Voice |
| #88 | 브리지·PATH·Tailscale OFF 복구 핵심 인수 통과 | 최초 소실 계기 미확정; Android 제약 보존 |
| Signed APK | [v0.1.47 / code48](https://github.com/al-hub/closepaw/releases/tag/v0.1.47), 설치 확인 | 문서 commit은 새 APK가 아님 |
| Open PR | [#33](https://github.com/al-hub/closepaw/pull/33) 한 개 | 최신 main 중복/고유 기능 대조 후 supersede 또는 작은 대체 PR |
| P3/P6/P8/P9 | LATER | 자동 착수하지 않음 |
| Projects #2 | 실제 필드·자동화 이번 감사 미검증 | [보드](https://github.com/users/al-hub/projects/2/views/1)에서 별도 확인 |

다음 Works는 [복사 가능한 시작 지침](WORKS_HANDOFF.md#8-다른-works에-복사할-시작-지침)으로
PR #33부터 정리한다. CI/릴리스/설치/실제 결과를 구분하며, 이슈가 OPEN인 것만으로
이미 검증한 기능을 미구현이라 하지 않는다. 반대로 코드 병합만으로 실기기 완료를
주장하지 않는다. 페어링·서명키·릴리스 태그·KUM 데이터는 유지한다.
