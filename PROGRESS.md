# ClosePaw Progress

This is the live project milestone board for the ChatGPT Android control path.

Progress work follows one rule: **implementation and this document move together**. A milestone is complete only when its real-device user experience has been verified.

## Current position

```text
Completed: 1 / 10

✅ P0 · MCP Connection
🟡 P1 · Browser Read MVP      ← ACTIVE
⏭ P2 · General Read
⬜ P3 · Kakao Read
⬜ P4 · Termux Execute
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

## P1 · Browser Read MVP — ACTIVE 🟡

**User-visible goal**

> “삼성 인터넷 현재 페이지 읽어줘.”

ChatGPT Voice/Text → Secure MCP Tunnel → ClosePaw → Accessibility text + visible-screen screenshot → ChatGPT → same conversation.

**Keep P1 deliberately small.** Read the currently visible page first. Add scrolling/full-page collection only if real-device use proves it is necessary.

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
- [ ] **Stable MCP Tunnel Spike — ACTIVE**
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
  - [ ] Validate secure-tunnel recovery across app restart, Wi-Fi↔5G change, and Galaxy reboot
  - [ ] Retire the old Quick Tunnel / `trycloudflare.com` connector only after recovery validation passes
- [x] ChatGPT Text E2E browser-read validation
- [ ] ChatGPT Voice E2E browser-read validation
- [ ] Decide from real use whether scroll/full-page collection is needed

### Current real-device checkpoint

The v0.1.18 protected-path experiment failed in both the already-open conversation and a fresh ChatGPT conversation. The plugin itself was updated to the exact endpoint displayed by ClosePaw, so the working diagnosis is now **MCP connection compatibility with the random path**, not conversation caching.

For P1, this protection was more complexity than the milestone needs. v0.1.19 therefore restores the standard `/mcp` endpoint shape that already passed Android Text/Voice `get_status()` in P0. Sensitive-app work remains out of scope until authentication is added later.

**Immediate next action:** run ChatGPT **Voice** → `read_app(app="samsung_internet")` → useful spoken response on the real Galaxy. Text-mode browser READ already passed on v0.1.33. Then validate secure-tunnel resilience in order: app restart → Wi-Fi↔5G → Galaxy reboot. Keep P1 ACTIVE until those real-device results are recorded. No Shizuku, ADB, wireless debugging, or CDP is required.

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
→ “삼성 인터넷 현재 페이지 읽어줘”
→ ClosePaw read_app
→ Samsung Internet content
→ ChatGPT response
```

with useful accuracy and acceptable latency.

## P2 · General Read — NEXT ⏭

Reuse the proven P1 path for:

1. Chrome
2. Gemini

Completion means the same minimal READ approach works across all three targets without building a second agent inside ClosePaw.

## P3 · Kakao Read — LATER ⬜

Read a bounded KakaoTalk conversation range and let ChatGPT summarize it. Add scrolling/dedup only as real use requires it.

## P4 · Termux Execute — LATER ⬜

Expose the already-proven Termux execution path through MCP with the smallest safe interface.

## P5 · PC Continuity — LATER ⬜

Termux → SSH → PC. Add persistent tmux/session behavior only when interruption/resume is proven to matter in real use.

## P6 · Toss Read — LATER ⬜

Limited financial READ only. Return the requested value or summary, not broad raw screen data. No transfer/payment/auth actions.

## P7 · Reliability — LATER ⬜

Fix the failures that appear in daily use: latency, recovery, UI changes, Voice continuity.

## P8 · App Extensibility — LATER ⬜

Generalize AppProfile/registry only after repeated app integrations reveal a real common pattern.

## P9 · Portability — LATER ⬜

Stable endpoint, reboot recovery, onboarding, and another Android-device validation.

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
