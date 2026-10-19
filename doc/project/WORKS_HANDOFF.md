# ClosePaw GitHub 정합성 감사 및 Works 인계

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
