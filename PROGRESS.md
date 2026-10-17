# ClosePaw Progress

This is the live project milestone board for the ChatGPT Android control path.

Progress work follows one rule: **implementation and this document move together**. A milestone is complete only when its real-device user experience has been verified.

## Verified checkpoint — 2026-10-17 (KST)

Last verified installed baseline: **v0.1.47 / code 48**, Termux runtime **88.2**.
Latest published APK: **v0.1.48 / code 49**, source `209dc9e20767cdfd39ca59ff2c7f1936de539579`.
[Signed APK](https://github.com/al-hub/closepaw/releases/download/v0.1.48/closepaw-aa-bridge.apk).
Device update and Gemini READ/Voice/draft acceptance remain pending.
Release source: `bb7442a8c577669f53b79028e1d6d747c09210f2`.
Documentation-only commits after this source do not change the installed APK.

- **P0/P1 DONE; P2 ACTIVE**: Chrome Voice and Samsung Internet READ accepted;
  Gemini native READ/Voice and draft guard acceptance remain outstanding.
- **P4 core execution VERIFIED**: authenticated MCP → Termux, plain `kv status`
  exit 0. Keep broader task-lifecycle/Voice acceptance separate; P4 is no longer
  an unimplemented future capability.
- **P5/P7 PARTIAL evidence**: PC/SSH/WSL/KUM healthy; v0.1.46 reboot restore,
  explicit Stop persistence across reopening, and Wi-Fi→mobile restore observed.
  This does not establish bidirectional network recovery or daily Voice reliability.
- **Issue #88 core recovery accepted**: v0.1.45 daemon SIGKILL recovery/token
  metadata retention and v0.1.47 Tailscale OFF → connection request → private
  target reachable → plain `kv status` exit 0. Original disappearance cause is
  still unproven; whole-app force-stop is an Android recovery boundary.
- **PR #33 CLOSED without merge (2026-10-17)**: all seven files reviewed against
  main `6dcb384`; capability gating/tests already exist and current setup/recovery UI
  supersedes the old flow. One-line curl convenience is deferred; old token overwrite,
  direct deployment and unsupervised launch are discarded. Per-file disposition is
  recorded in the [PR description](https://github.com/al-hub/closepaw/pull/33).
  Next: Gemini real-device READ/Voice and draft guard acceptance; P2 remains ACTIVE.

[Audit, evidence, residual tasks and copyable Works handoff](doc/project/WORKS_HANDOFF.md)
are the current summary. Older dated entries below are historical checkpoints,
not instructions to downgrade, re-pair, refresh plugins or repeat passed tests.

## Gemini host read repair — v0.1.48 RELEASED; device acceptance pending

- Real v0.1.47 READ after unlock rejected the Google host package
  (`target_not_foreground`, request `f17acf1b-bc7a-4b8c-b57a-160b9bf747fb`);
  no response text or screenshot was captured.
- Candidate reuses the existing Gemini-specific composer identity guard for reads,
  rejects generic Google Search and discards captures if identity changes.
- [Repair and acceptance](doc/dev/gemini-hosted-read-v0148.md); PR #93.
  PR #93 merged. [Main CI](https://github.com/al-hub/closepaw/actions/runs/39023231120)
  and [Signed Release](https://github.com/al-hub/closepaw/actions/runs/39023499964) passed.
  Same-signer verification and APK/checksum publication passed. APK SHA-256:
  `9045eaa1afeeb065c3dc95310295ec523168dc8af59f31e74a0eefc04ed24bef` (116,306,905 bytes).
  Device installation, actual Gemini READ/Voice and draft acceptance remain pending.
- The next full-control plan is documented on a separate planning branch:
  [Gemini goal execution and verified round trip](https://github.com/al-hub/closepaw/blob/docs/gemini-roundtrip-plan-20261017/doc/project/GEMINI_ROUNDTRIP_PLAN.md).
  Representative test: ask Gemini for current location; control round-trip and location
  correctness are separate verdicts. Plan only; new feature/parallel-agent development
  has not been authorized or started. Build-time optimization is deferred.

## Current position

```text
Completed: 2 / 10

✅ P0 · MCP Connection
✅ P1 · Browser Read MVP
🟡 P2 · General Read            ← ACTIVE
⏭ P3 · Kakao Read
🟡 P4 · Termux Execute          ← CORE VERIFIED; lifecycle acceptance pending
⬜ P5 · PC Continuity
⬜ P6 · Toss Read
⬜ P7 · Reliability
⬜ P8 · App Extensibility
⬜ P9 · Portability
```

## Minimal runtime baseline

The default phone path is intentionally limited to:

```text
ChatGPT
→ OpenAI Secure MCP Tunnel
→ ClosePaw
→ Android Accessibility + one bounded screenshot
→ ChatGPT text/vision
```

Required on the Galaxy: **ClosePaw + Android Accessibility permission only.**

Shizuku, ADB, wireless debugging and Chrome CDP are **not required by the default ChatGPT control/read path**. Existing CDP infrastructure may remain as an isolated experimental capability, but P1/P2 must not depend on it.

Design rule: prefer one generic Android perception path over app-specific privileged transports unless a real user requirement cannot be met otherwise.

## Status vocabulary

- `DONE` ✅ — verified end-to-end
- `ACTIVE` 🟡 — current milestone
- `NEXT` ⏭ — immediate next milestone
- `LATER` ⬜ — intentionally deferred
- `BLOCKED` 🔴 — cannot progress without resolving a concrete blocker

## P0 · MCP Connection — DONE ✅

**Goal:** ChatGPT Android Text/Voice can call ClosePaw on the same phone through custom MCP.

Verified baseline:

- [x] Android ChatGPT text → ClosePaw `get_status()`
- [x] Android ChatGPT Voice → ClosePaw `get_status()`
- [x] Same-phone ClosePaw returns actual app/version status
- [x] No laptop, KUM, Termux, custom Voice UI, or external controller required

## P1 · Browser Read MVP — DONE ✅

**User-visible goal**

> “삼성 인터넷 현재 페이지 읽어줘.”

ChatGPT Voice/Text → Secure MCP Tunnel → ClosePaw → Accessibility text + visible-screen screenshot → ChatGPT → same conversation.

**P1 simplified scope (2026-10-11): one visible-screen snapshot per request.**
After bringing Samsung Internet to the foreground, settle the WebView briefly, capture **one JPEG**, and use Accessibility or bounded on-device Korean OCR from **that same JPEG**. Return visible text and image to ChatGPT Voice; describe only what was actually captured. If the article body is absent, report the gap rather than substitute a web-search summary as if it came from the current page.

A user may navigate before, during, or after the request: the chosen snapshot is authoritative for that READ. No URL/tab-tracking state machine, change detection, full-page crawling, or automatic scrolling in P1. Any later navigation is handled by a new READ. Recovering from app restart, Wi-Fi↔5G switching, and device reboot belongs to **P7 Reliability, Issue #74**, not the P1 completion gate.

### Implementation

- [x] Progress naming and live board established
- [x] v0.1.18 protected-path experiment recorded
  - existing chat and fresh chat failed while the direct Custom MCP registration still pointed at an older endpoint
  - therefore random-path incompatibility was **not** proven
- [x] v0.1.19 restores the previously proven standard `/mcp` endpoint for P1
- [x] `read_app(app="samsung_internet")` MCP contract
- [x] Samsung Internet launch via existing Android platform
- [x] Visible Accessibility-tree text capture
- [x] Fail closed if Samsung Internet is not actually foreground
- [x] CI passes
  - Security CI passed after the compile-fix rerun
- [x] Signed v0.1.18 P1 APK published
- [x] v0.1.18 installed on the target Galaxy — user-confirmed 2026-10-07
- [x] ClosePaw v0.1.18 Voice bridge showed Connected with a protected endpoint
- [x] Private ChatGPT plugin was updated to closepaw-bridge v0.1.1 with that endpoint
- [x] Fresh-chat retry also failed, proving this was not only an active-conversation cache issue
- [x] v0.1.19 standard-/mcp compatibility release CI/release
- [x] v0.1.19 signed APK published
- [x] v0.1.19 installed on the target Galaxy — user screenshot confirmed Connected
- [x] Private `closepaw-bridge` plugin package refreshed to the v0.1.19 Quick Tunnel `/mcp` URL
  - closepaw-bridge plugin bumped to v0.1.2
- [ ] Direct custom MCP registration `closepaw mcp` still needs its Server URL refreshed to the current Quick Tunnel `/mcp` endpoint
- [x] **Stable MCP Tunnel integration — connected; recovery deferred to P7**
  - [x] Build OpenAI `tunnel-client-runtime` v0.0.15 for `android/arm64` in CI
    - ELF64 AArch64 PIE, interpreter `/system/bin/linker64`
    - SHA256 `d25d4f8977ec93089dfed41667acd91ab6174042e8c6943428a150dca8895288`
  - [x] Package it into a signed ClosePaw Galaxy probe build
  - [x] Run the signed probe APK on the Galaxy and execute `Secure Tunnel Probe` — PASS on v0.1.21 (2026-10-08)
  - [x] Wire the bundled runtime to `http://127.0.0.1:18424/mcp` in-app (PR #53)
  - [x] Create/use a ClosePaw-specific stable `tunnel_id` and configure a restricted runtime key on Galaxy
  - [x] Resolve Android DNS failure for `api.openai.com` with loopback CONNECT proxy on `127.0.0.1:18426`
  - [x] Validate OpenAI control-plane polling with zero consecutive failures and successful tunnel metadata fetch
  - [x] Register ChatGPT custom MCP `closepaw-tunnel` using Connection: Tunnel
  - [x] Validate ChatGPT → Secure MCP Tunnel → Galaxy → ClosePaw `get_status()` end-to-end on v0.1.27
  - [x] Transfer app-restart, Wi-Fi↔5G, and reboot recovery validation to P7 Reliability (#74)
  - [x] Transfer retirement of the old Quick Tunnel connector after recovery validation to P7 (#74)
- [x] ChatGPT Text E2E browser-read validation
- [x] ChatGPT Voice E2E browser-read validation — 2026-10-11 17:39 KST, user-provided source/Voice screenshots, 19s
- [x] Defer scrolling/full-page collection outside P1 unless a separate user need is established

### Current real-device checkpoint

The v0.1.18 protected-path experiment failed in both the already-open conversation and a fresh ChatGPT conversation. The plugin itself was updated to the exact endpoint displayed by ClosePaw, so the working diagnosis is now **MCP connection compatibility with the random path**, not conversation caching.

For P1, this protection was more complexity than the milestone needs. v0.1.19 therefore restores the standard `/mcp` endpoint shape that already passed Android Text/Voice `get_status()` in P0. Sensitive-app work remains out of scope until authentication is added later.

**Final Galaxy checkpoint (2026-10-11): P1 DONE.** User provided a Samsung Internet screenshot of a Maeil Business Newspaper article with several visible Korean paragraphs about US/China AI competition, Korea's survival strategy, and Lee Kwan-ho's eight-pillar THRIVING framework; a corresponding ChatGPT Voice screenshot showed the request “삼성 인터넷 현재 화면을 세 문장으로 요약해줘”, the tool step “Read Samsung Internet current page content”, and a three-sentence answer matching those exact subjects after **19 seconds**, with no external website search step shown. v0.1.37 (38) was independently verified installed earlier and returned one screenshot (`screenshot_attempts=1`) with Korean OCR/MCP image and text. The screenshots do not expose a correlated internal Voice request ID, but the visible tool step and semantic match satisfy the agreed user-visible single-screen MVP. Do not infer full-article or off-screen coverage. Tunnel resilience and Quick Tunnel retirement remain P7 tasks. No Shizuku, ADB, wireless debugging, or CDP is required.

### Cross-cutting acceleration · ClosePaw self-management over MCP

Add a small, safe management surface so future app-by-app validation does not require repeated manual ClosePaw handling on the Galaxy.

Planned capabilities:

- [ ] `get_update_status()` — report installed version and latest available signed release
- [ ] `install_update()` — invoke the existing ClosePaw updater and advance as far as Android security rules allow
- [ ] `restart_bridge()` — restart/reconnect the MCP + tunnel runtime without opening the app manually
- [ ] `get_diagnostics()` — return tunnel, local MCP, Accessibility and updater health in one read-only response
- [ ] Determine the exact Android boundary for unattended APK install; keep any required system confirmation explicit
- [ ] After validation, use this path for P2+ regression loops: code → CI/release → device update → reconnect → E2E test

Design rules:

- Reuse the existing updater/bridge implementation; do not build a second update engine.
- Keep status/diagnostics read-only.
- Treat update/restart as explicit state-changing operations with appropriate confirmation/policy.
- Do not let self-management block the current P1 browser-read fix, but complete it before broad multi-app expansion where practical.

### P1 completion criterion

P1 is `DONE` only when a real Galaxy can complete:

```text
ChatGPT Voice
→ “삼성 인터넷 현재 화면을 세 문장으로 요약해줘”
→ ClosePaw read_app
→ one Samsung Internet screenshot and its Accessibility / local OCR text
→ ChatGPT response grounded in the captured visible text
```

**Met on Galaxy 2026-10-11, 17:39 KST:** 19s visible Voice run; 3 sentences correctly reflect the independently supplied visible article paragraphs; the Voice UI shows a Samsung Internet content-read tool and no 12-site web search. Separate connected v0.1.37 probe established one JPEG (`screenshot_attempts=1`) + Korean OCR/MCP. A per-Voice-tool request ID was not visible in the screenshot; this was accepted under the deliberately simplified **user-visible** single-snapshot P1 gate, not a forensic tool trace requirement. No full-page or off-screen content guarantees. App restart / Wi-Fi↔5G / reboot resilience remains untested and is deferred to P7.

## P2 · General Read — ACTIVE 🟡

**Goal:** reuse P1's one visible-screen snapshot / Accessibility / offline Korean OCR MCP path for Chrome and Google Gemini, preserving Samsung Internet regression. No Shizuku, ADB, CDP, background extraction, URL tracking, or full-page scrolling.

**Implementation/release gates (v0.1.38 / code 39):**
- [x] Independently verify existing v0.1.37 Galaxy Chrome new-tab Accessibility + screenshot READ works (UI text only, not Chrome article Voice E2E).
- [x] TDD coverage for explicit package allowlist, aliases, and MCP Gemini discovery.
- [x] Add read_app(app="gemini") for Google's standalone Android package `com.google.android.apps.bard` and keep foreground-package verification (fail closed on unexpected UI ownership).
- [x] Share exactly one bounded JPEG across Chrome, Gemini, and Samsung Internet; local OCR fallback when Accessibility lacks meaningful content.
- [x] Branch Security CI and OpenAI Secure MCP Android build PASS (PR #82).
- [x] Main Security CI + signed v0.1.38 Release/signer/assets PASS ([release](https://github.com/al-hub/closepaw/releases/tag/v0.1.38)).
- [x] v0.1.38 installed on real Galaxy; connected `get_status` confirmed 0.1.38 (39) (2026-10-12).
- [x] Galaxy Chrome visible article text + same-conversation ChatGPT Voice grounded response PASS (2026-10-12, 16s screenshot of Chrome tool step and accurate 3-sentence summary).
- [ ] Galaxy Gemini visible reply/screen text + same-conversation ChatGPT Voice grounded response PASS.
- [x] Galaxy Samsung Internet screenshot+OCR regression PASS (2026-10-12, direct `read_app`, one screenshot, local Korean OCR).
- [ ] Document timing/request evidence; mark P2 DONE and close Issue #69 only after device Voice acceptance.

**Current checkpoint (2026-10-12):** Signed v0.1.38 release published and installed (Galaxy version code 39), Samsung Internet direct OCR regression PASS, Chrome article direct MCP READ PASS (two one-screenshot captures), and ChatGPT Voice Chrome three-sentence summary user-visible PASS (16s). The direct Chrome capture contained a Dong-A Ilbo article on Unification Minister Jeong Dong-yeong; the user-provided Voice screenshot displayed the Chrome READ tool step and three accurate sentences matching the same article. No internal request ID correlation is claimed. An unrelated headline appeared in Chrome Accessibility UI text but not in the Voice summary. **Gemini app READ + Voice summary remain pending**. The connected `closepaw_galaxy.read_app` tool schema still exposes only Chrome and Samsung Internet, despite v0.1.38 app code supporting Gemini. P2 remains ACTIVE; P7 retains tunnel recovery.

**Next action (2026-10-17):** use the existing operation envelope to discover
`app.screen.read` and its current schema, then verify native Gemini visible reply
READ and a grounded Voice response. Historical stale `read_app` enums are not a
reason to recreate the plugin. Close P2 only with actual matching screen evidence.

### Chrome Voice evidence · 2026-10-12 18:13 KST

- User-provided ChatGPT Android screenshot shows “ClosePaw로 크롬 현재 화면을 읽고 세 문장으로 요약해줘”, the “Chrome 브라우저 내용을 읽음” tool step, **16초 동안 처리함**, and three grounded sentences on Jeong Dong-yeong, US-Korea security/trade disagreements, the alliance and North Korea uranium information sharing. These match the previously captured Chrome article text.
- Separate connected `read_app(chrome)` probes succeeded with `accessibility_webview_count=1`, `screenshot_attempts=1` and attached JPEGs. Request IDs: `f70e16df-3538-4ffe-b1b4-38e29ccdad67` and `250710b1-0b8a-430d-8ec1-a6813fee3f40`. The screenshot does not expose a correlated internal Voice request ID, so no forensic trace linkage is asserted.
- **Chrome Voice user-visible gate CLOSED; P2 remains ACTIVE.** Gemini still needs successful direct READ and matching Voice output. Issue #69 remains open.

### Gemini Android native-app reader · v0.1.39 follow-up (pending CI and device acceptance)

The target is the **installed Google Play Android Gemini app** (`com.google.android.apps.bard`), not a browser tab or Gemini API. The user's 2026-10-12 Galaxy screenshot shows a native model picker, not a generated answer.

- [x] Test-first native Gemini screen-state policy: model selection popup, empty composer, response generating, visible conversation with unknown speaker roles, and toolbar-only fallback.
- [x] Keep shared Accessibility + single JPEG + local Korean OCR; route only Gemini through an explicit native-app assessment instead of `BrowserReadQuality`.
- [x] Return `screen_kind` and `reply_status` for Gemini, with a model-facing notice that a selector/composer is not an AI response and that unlabelled conversation text cannot prove speaker roles.
- [x] Stage unique signed release version 0.1.39 (40).
- [ ] Branch CI, review/merge, main Security CI, signed v0.1.39 APK/sha256/signature checks.
- [ ] Real Galaxy install, `get_status` 0.1.39 (40), and native Gemini direct READ/Voice grounded in a visible reply.
- [ ] Confirm the ChatGPT custom MCP connection exposes `read_app(app="gemini")`; the active `closepaw_galaxy` tool manifest may still have a stale `app` enum. Do not claim on-device failure from a stale client schema.

Do not mark P2 DONE until a real Gemini conversation response and Voice answer are matched. This is not Gmail, web search, Google Search, or Gemini Live. Avoid remote OCR, background extraction, and guessed reply attribution.

## P3 · Kakao Read — LATER ⬜

Read a bounded KakaoTalk conversation range and let ChatGPT summarize it. Add scrolling/dedup only as real use requires it.

## P4 · Termux Execute — CORE VERIFIED / acceptance review 🟡

PR #85 exposes authenticated durable jobs through the fixed MCP operation envelope.
On v0.1.47, `command -v kv && kv status` succeeded before and after Tailscale
recovery (exit 0). No absolute-path workaround remains. Task cancellation,
interrupted-job re-observation, duplicate-request and Voice-specific device checks
must be recorded independently; do not infer them from this read-only smoke test.
See [current acceptance matrix](doc/project/WORKS_HANDOFF.md).

## P5 · PC Continuity — LATER ⬜

Termux → SSH → PC. Add persistent tmux/session behavior only when interruption/resume is proven to matter in real use.

## P6 · Toss Read — LATER ⬜

Limited financial READ only. Return the requested value or summary, not broad raw screen data. No transfer/payment/auth actions.

## P7 · Reliability — PARTIAL evidence / remaining scope deferred ⬜

Own long-running reliability separately from P1 single-snapshot READ:

- [x] v0.1.46 Galaxy reboot → unlock → user starts Tailscale → ClosePaw tunnel and Termux recover without Connect
- [x] v0.1.46 Stop → home → reopen stays Stopped; manual Connect resumes execution
- [x] v0.1.46 Wi-Fi→mobile restores remote execution without manual Connect (exact latency unmeasured)
- [ ] Process death/app restart recovery while connection is intended to remain enabled
- [ ] Mobile→Wi-Fi, prolonged total internet loss, and Stop→reboot persistence
- [ ] Reboot combined with v0.1.47 task-directed Tailscale recovery (without manually launching Tailscale)
- [ ] Voice continuity and performance under daily-use app/UI changes
- [ ] Retire old Quick Tunnel connector only after the recovery path is verified

Track implementation and real-device evidence in [Issue #74](https://github.com/al-hub/closepaw/issues/74).

## P8 · App Extensibility — LATER ⬜

Generalize AppProfile/registry only after repeated app integrations reveal a real common pattern.

## P9 · Portability — LATER ⬜

Stable endpoint portability, onboarding, and another Android-device validation; routine reboot-recovery testing is tracked in P7.

## Working rule

Do not move work forward because code exists. Move the milestone only when the user-visible path is verified.

During Progress work, report in chat using the compact form:

```text
현재: P1 · Browser Read MVP
완료: <what just became verified>
다음: <one immediate next action>
```

- 2026-10-08: v0.1.23 diagnostics: distinguish runtime readiness from E2E MCP discovery; expose readyz, control-plane health, MCP health, and local initialize self-test on Galaxy.

- 2026-10-08: v0.1.25 control-plane diagnostics: expose /api/status and recent redacted tunnel-client logs on-device after v0.1.24 showed local MCP initialize success but control-plane degraded/backoff/network_error.

- 2026-10-08: v0.1.26 runtime log capture: runtime flavor omits /api/status and /api/logs, so ClosePaw now captures redacted tunnel-client stdout/stderr and health details for control-plane diagnosis.

- 2026-10-08: v0.1.27 Android DNS proxy: root cause confirmed as tunnel-client resolving api.openai.com via [::1]:53 on Galaxy. Added loopback-only CONNECT proxy (127.0.0.1:18426) restricted to api.openai.com:443 and wired CONTROL_PLANE_HTTP_PROXY so Android/Java performs DNS while MCP remains direct.

- 2026-10-08: **Stable MCP Tunnel E2E PASS on v0.1.27.** Galaxy tunnel-client control-plane changed from `degraded/backoff/network_error` to active polling with `consecutive_failures=0`; runtime logs confirmed `uses_proxy=true`, `mcp session initialized`, and `tunnel metadata fetched`. ChatGPT custom MCP `closepaw-tunnel` was created with Connection: Tunnel, and ChatGPT successfully called `get_status()` through OpenAI Secure MCP Tunnel; ClosePaw returned version 0.1.27 (28).

- 2026-10-08: Reusable architecture note added at `doc/main/bridge/reusable-chatgpt-android-secure-mcp.md` so the verified ChatGPT ↔ Android Secure MCP transport pattern can be reused outside ClosePaw.


- 2026-10-08: **Architecture simplification decision.** Browser READ no longer depends on Shizuku, ADB, wireless debugging, or Chrome CDP. The default path is Secure MCP Tunnel → ClosePaw → Accessibility text + one bounded screenshot → ChatGPT text/vision. MCP-specific Chrome CDP adapter and Samsung Internet DevTools probe are removed from the default path.


- 2026-10-09: Browser capture stabilization: read_app no longer relaunches a browser that is already foreground. Samsung Internet gets one bounded 800 ms screenshot retry after the initial capture to allow the WebView surface to settle. No Shizuku, ADB, wireless debugging, or CDP added.


- 2026-10-09: **Samsung Internet screenshot stabilization PASS on v0.1.33.** read_app(app="samsung_internet") returned status=succeeded, capture_source=accessibility_plus_screenshot, screenshot_attached=true, screenshot_attempts=2, and the returned JPEG visibly contained the actual mobile web page content instead of a blank/black WebView region. This validates the minimal no-Shizuku/no-ADB/no-CDP browser READ path for both Chrome and Samsung Internet in ChatGPT text mode.

- 2026-10-10: **P1 pre-device regression hardening — verification pending.** Latest main `46ab625` has passing Security CI ([run 38022073569](https://github.com/al-hub/closepaw/actions/runs/38022073569)) and a successful signed-release workflow ([run 38022362654](https://github.com/al-hub/closepaw/actions/runs/38022362654)); signed [v0.1.34](https://github.com/al-hub/closepaw/releases/tag/v0.1.34) APK and checksum are published. This does **not** prove that v0.1.34 is installed or that Voice READ works. Added focused tests for MCP image-block forwarding/failure responses and a password-aware Accessibility-text filter on the P1 branch; its own CI and Galaxy E2E must be checked independently. An attempted direct Galaxy `get_status` probe returned `MCP SSE probe 429`, so no new device result was obtained. Existing v0.1.33 Text E2E PASS remains the last documented browser-read evidence; Voice E2E and tunnel recovery are **NOT VERIFIED**. P1 remains ACTIVE.

- 2026-10-10: **Galaxy v0.1.34 device evidence — transport PASS, Voice READ pending definitive tool correlation.** User-provided Galaxy Tunnel Diagnostics at 07:45 UTC: `readyz HTTP 200`, `local-mcp-initialize HTTP 200` with server `ClosePaw 0.1.34`, `consecutive_failures=0`, `mcp session initialized`, and `tunnel metadata fetched`. Direct ChatGPT-connected `get_status()` independently returned version `0.1.34 (35)`; direct `read_app(app="samsung_internet")` independently returned `status=succeeded`, `screenshot_attached=true`, `screenshot_attempts=2` (request `e0109fd4-ba7d-4555-a158-b572dfc7c629`). The user's ChatGPT Voice screenshot shows the command “삼성인터넷 현재페이지 읽어줘” answered with an article summary after 57 seconds; no linked `read_app` invocation ID or matching screenshot/page evidence was supplied from that same Voice turn, so treat as **Voice-response observed, full Voice E2E not yet independently proven**. The separate `read_app` capture's Accessibility text contained Samsung Internet address bar/navigation only, despite `text_read_status=ok`: a false-positive classifier, addressed by targeted tests/fix on [draft PR #77](https://github.com/al-hub/closepaw/pull/77). The 2026-10-10 PR Security CI already passed before this follow-up; rerun/check new head before any merge. Legacy `mcp-health: same_child_evidence_unavailable` does not override successful initialize and direct tool calls. Tunnel recovery across app restart, Wi-Fi↔5G, and reboot remains untested. Keep P1 ACTIVE and #68 open.

- 2026-10-10: **v0.1.35 release candidate prepared, P1 remains ACTIVE.** Draft PR #77 now includes the smallest browser-only quality/privacy fix, MCP image contract tests, and `VERSION_NAME=0.1.35` / `VERSION_CODE=36` to avoid a collision with the immutable v0.1.34 tag. Both prior-head PR checks passed ([Security CI](https://github.com/al-hub/closepaw/actions/runs/38036034240), [Android tunnel build](https://github.com/al-hub/closepaw/actions/runs/38036034232)); verify the new version-bump head's CI before merge. After passing main Security CI, the existing Signed Release workflow must create a verified-signer v0.1.35 APK/checksum. **Release publication and installation are not milestone completion.** Galaxy installation, useful same-conversation Voice READ, and app-restart / Wi-Fi↔5G / reboot recovery remain open acceptance gates. Do not mark P1 DONE or close Issue #68 until actual device evidence is recorded.

- 2026-10-11: **P1 Samsung Internet blog root-cause isolation and v0.1.36 release candidate — NOT DEVICE VERIFIED.** Galaxy v0.1.35 `read_app(samsung_internet)` returned Accessibility text containing only browser chrome (including `사용 중, 읽기 모드`), `accessibility_webview_count=0` and an incorrect `reason_code=ok`. An independently retrieved 472×1024 MCP JPEG **visibly showed the real Naver blog article title and Korean paragraph body**, so display screenshot capture and MCP image-block transport worked in the text inspection, but the user's Voice output still reported an unreadable article (8s and 6s attempts). Exact Voice-tool-call correlation and Voice image-consumption ability are **not proven**. New focused branch adds chrome-label regression and bounded **on-device, bundled Korean screenshot OCR** only when Samsung Accessibility has no page text, with a legible 1536px capture in that case and a normal MCP text result for Voice clients that may not process MCP image blocks. This adds an offline model, not an external OCR server, ADB, Shizuku, CDP, full-page scrolling, or background extraction. Tests/CI/Release and actual Korean OCR accuracy **require independent verification**. Candidate version v0.1.36 (37), P1 remains ACTIVE. Next: branch Security CI + tunnel build → review/merge → main CI → signed release/signer checks → Galaxy update → user Voice article content check; then app restart, Wi-Fi↔5G and reboot recovery checks. Do **not** close Issue #68 or mark P1 DONE until the Galaxy confirms useful content and recovery.

- 2026-10-11: **P1 scope simplification approved; v0.1.37 candidate (not yet released or device-verified).** Treat each Samsung Internet READ as one settled, bounded visible-screen snapshot; use the same JPEG for MCP image and local Korean OCR. Keep screen text in the MCP response so ChatGPT Voice can summarize it. No URL/tab-change tracking, scrolling, external search substitution, app/network/reboot recovery gate in P1. Recovery and old Quick Tunnel retirement moved to P7 #74. Previous v0.1.36 real-device proof: `get_status`, JPEG, on-device Korean OCR and MCP text transport pass; accurate same-page Voice response remains **unverified** (a prior Voice attempt used search across 12 external sites after missing the article body). v0.1.37 PR CI/release/installed build and the final Voice result must be verified before marking P1 DONE.

- 2026-10-11 17:39 KST: **P1 DONE — first meaningful Samsung Internet → ChatGPT Voice current-screen READ E2E verified on Galaxy v0.1.37 (38).** User screenshots show Samsung Internet's opened Maeil Business Newspaper article with readable paragraphs about US/China AI competition, survival strategies for Korea, and eight pillars of Lee Kwan-ho's THRIVING framework. Corresponding ChatGPT Voice screenshot shows the request “삼성 인터넷 현재 화면을 세 문장으로 요약해줘”, the tool action **“Read Samsung Internet current page content”**, and a three-sentence grounded summary after **19 seconds**; no external multi-site web search step is displayed. Earlier v0.1.37 direct MCP probe on the same Galaxy independently returned `screenshot_attempts=1`, on-device Korean OCR (`text_source=on_device_ocr`) and matching screenshot text, although it captured a different scroll position and has no correlated request ID from the Voice turn. Under the explicitly simplified P1 single-visible-screen acceptance, this constitutes useful same-conversation Voice success. **P1 CLOSED, P2 NEXT.** Preserve limitations: one screen only, no cross-page / full-article guarantee, and network/restart/reboot resilience is P7 #74, not P1. Evidence: user-supplied two screenshots in 2026-10-11 ChatGPT conversation, PR #80, release v0.1.37, Issue #68.

- 2026-10-11: **P2 General Read implementation started (v0.1.38 candidate).** Existing v0.1.37 Galaxy Chrome new-tab READ returned Accessibility text + a screenshot, but not a Chrome article Voice demonstration. Candidate adds explicit Google Gemini read_app target using package `com.google.android.apps.bard` and generalizes the single screenshot/Accessibility/OCR path across all three apps. Preserve the P1 DONE baseline. The release must pass CI and Galaxy install, and P2 remains ACTIVE until grounded Chrome and Gemini same-conversation Voice reads plus Samsung Internet regression are documented. The v0.1.37 repeated Signed Release failure on doc-only newer commits is due to immutable tag mismatch and does not indicate a broken published APK. P7 owns connection resilience.

- 2026-10-13: **P2 v0.1.40 candidate — stable MCP app contract.** Keep `read_app({app: string})` and remove app-name enum and app-specific discovery descriptions. The runtime read-target registry is the single source used by dispatch and additive `get_status.supported_read_apps`; this describes configured support, not installation or permission readiness. Preserve Samsung Internet/Chrome/native Gemini screen-state capture paths, safe aliases and unsupported-app rejection. Initial migration must verify actual connected tools/list and may require a one-time connector-definition refresh; subsequent additions to the same read capability must not change the MCP contract. Local Gradle execution blocked by dependency network access; Android tests, signed-release identity/checksum and APK publication require GitHub CI. External tunnel status probe returned 404 in this conversation, so real-device Gemini READ/Voice acceptance remains unverified. P2 stays ACTIVE until installed APK + actual connected contract + Gemini Voice screen-read pass.


## v0.1.42 Gemini draft expansion (in validation)

Adds gemini.draft.insert behind a dedicated local opt-in, preserving all eight MCP tool definitions and existing pairing/tunnel settings. Includes empty-composer guards, verification and persistent metadata-only diagnostics. See doc/dev/gemini-draft-expansion-acceptance.md. CI/release and real-device update acceptance are tracked separately; installation acceptance remains pending.
