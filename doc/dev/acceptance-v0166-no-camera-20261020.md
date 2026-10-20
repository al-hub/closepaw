# v0.1.66 camera-excluded device acceptance and diagnostics

Date: 2026-10-20 KST. Installed version0.1.66/code67 independently confirmed. User requested all non-camera validation and logs for later fixes. Existing Notes/Clock fixes remain HOLD. This is an executed acceptance matrix with explicit limitations, **not a declaration that all tests passed**. No code, APK or release tag changed.

## Scope and results

| Area | Result | Evidence / limitation |
| --- | --- | --- |
| Camera | EXCLUDED | Corporate security instruction. No launch/capture or camera-permission change. |
| Version / runtime settings | PASS | Durable installed status; revision4 original settings. |
| Calculator native click and guarded tap | PASS, same installed-version evidence reused | 1+6=7; one tap and three clicks;529–653ms device totals, median557ms. Four submit/get pairs28.7s; no matched old-version baseline. |
| Home / cross-app navigation | PARTIAL | Four new Home actions succeeded (plus the earlier same-version Home check); Kakao and Maps launch confirmed. Final calculator-return click refused before execution. |
| Kakao first-screen read | PASS | Package com.kakao.talk; complete scan,74 exported nodes. No message opened/sent. Content not published. |
| Chrome registered screen read | PASS for visible screen |148 nodes/35 text nodes,457 captured characters,reason=ok. No full-page claim. |
| Samsung Internet registered screen read | PARTIAL / content_missing |48 nodes/11 text nodes, OCR attempted, screenshot obtained, no meaningful body. This is current-screen evidence, not proof every website fails. |
| Gemini registered screen read | PASS for captured text | Native conversation text retrieved. Initial read_identity=false metadata coexisted with status=ok; reason needs investigation before relying on identity diagnostics. |
| Gemini question/send/reply | PASS | One synthetic request, sent=true, unknown=false, reason=reply_completed. Numbers1–24 and final marker independently checked. |
| Long Gemini content coverage | PASS for this reply |980 characters; all24 numbered lines plus end marker in collected output. Screenshot visually confirmed bottom/end marker. coverage=complete,pages_collected=1; this does **not** exercise multi-page text assembly. |
| Generic Gemini scrolling | PASS after temporary app grant | Initial runtime_interaction_not_allowed/action_attempted=false. Temporary revision5 grant allowed scroll,547ms, screen_changed=true. |
| Generic model popup/tap | NOT ACCEPTED | Two separate fresh-observation attempts refused screen_changed/action_attempted=false (209/137ms). No popup/model change occurred. No further retries. |
| Maps public search/input/select | PASS with transition issue | Launch action acknowledged but post-observation secure_window_changed/unknown; new observation confirmed Maps, no launch replay. Empty search field filled with public station name; result row selected; fresh returned screen contained station title. |
| Settings restoration | PASS | Temporary revision5 rollback task succeeded to revision6. Independent settings read equals original config exactly, including original app grants. |
| Termux bridge | PASS | id/uname, exit0; bridge-reported command duration558ms. Bounded tag-filtered logcat yielded no ClosePaw entries; **no app-private logcat was obtained**. |
| Transport | INTERMITTENT FAILURE, recovered | Observe and diagnostics each timed out around20.4s, UNAVAILABLE. One later status read succeeded; no re-registration/reinstall. Subsequent queries/actions succeeded. |
| Indicator visible appearance / disappearance | UNVERIFIED | User answered “directly not observed”. Generic observation excludes indicator by design; registered query strips image from its operation result. Legacy read_app screenshot confirms Gemini reply only and is not evidence that the indicator appeared. |
| Human touch pass-through / foreign overlay | UNVERIFIED | No human touch or foreign-overlay intervention performed. Calculator guarded tap acceptance is a separate fact. |
| Cancel / service interrupt / lock / secure-window refusal | NOT EXECUTED in this session | No tool exposes controlled service interruption, physical lock or foreign-overlay setup for these acceptance cases. Existing CI coverage retained; real-device lifecycle/safety acceptance cannot be inferred. Transient secure_window_changed is not a deliberate secure-screen or locked-device test. |
| Notes input / complete Clock enumeration | HOLD | Prior defects retained; no note edit or alarm change performed. |

## Gemini timing evidence

Task `a915155e-9171-404f-82bc-db86a7de6388`.
Persisted stage elapsed_ms: not_sent298; submitting1369; send_action_invoked1902; waiting2232; sent10208; completed10285. Task started-to-completed10,605ms; submission call started-to-device-completed14,459ms (mixed clocks, approximate). Reply collection_ms4,235; page count1; completion_evidence=paged_start_overlap_end_and_stable. Diagnostic “sent” is recorded near completion and must not be interpreted as the actual send timestamp. Actual generation-finished timestamp is not exposed, so generation-end-to-read latency cannot be measured precisely.

## Generic action log

Device total includes fresh security validation at native boundary; native_action includes that work. Wall time is measured around execute plus the first get_task, and excludes prior observe, reasoning and speech. These are heterogeneous operations; do not combine them into a claimed p95 or improvement percentage.

| Action | State / reason | Device total ms | Submit + get wall ms | Task |
| --- | --- | ---: | ---: | --- |
| home | succeeded / system_button_acknowledged | 1789 | 6644 | `d6383209-defd-4ae6-96af-7438016fa8f2` |
| gemini-scroll-up | failed / runtime_interaction_not_allowed | 364 | 7812 | `899e987c-0541-4cce-a760-6e6704206d2e` |
| gemini-scroll-up-granted | succeeded / action_acknowledged | 547 | 7143 | `5b8382f7-3436-4da2-99d5-93882f0d56f8` |
| gemini-model-popup | failed / screen_changed | 209 | 8038 | `d805146c-4a7e-465a-9466-25a8249d7314` |
| gemini-model-popup-fresh | failed / screen_changed | 137 | 6790 | `f515e2ef-33c9-4e08-8d0e-6209a39594ec` |
| home-after-gemini | succeeded / system_button_acknowledged | 2511 | 7623 | `5814e6c9-1d0a-412c-b1f1-c7ea9549cf3a` |
| kakao-first-screen | succeeded / action_acknowledged | 2209 | 6188 | `97933f70-e059-4859-84a5-5d2f7118cfd2` |
| home-after-kakao | succeeded / system_button_acknowledged | 2240 | 7920 | `fa55b7fe-7851-4a70-ac1f-02692b7c8034` |
| maps-folder | succeeded / action_acknowledged | 3325 | 7860 | `7c12ed8d-0d05-49cd-b1ef-6f337b7fcb4f` |
| maps-launch | outcome_unknown / action_acknowledged | 2134 | 7703 | `03158b96-0664-4555-a692-9450153bb773` |
| maps-search-open | succeeded / action_acknowledged | 1321 | 9286 | `18de18dc-4018-45f9-ba71-0e6a7daa37a2` |
| maps-search-input | succeeded / action_acknowledged | 1299 | 6995 | `aa83fe6f-d721-45f3-9787-e019fe3af5b5` |
| maps-search-select-row | succeeded / action_acknowledged | 1572 | 7789 | `ee574a5f-1b81-4f53-991e-8adc268b1ea6` |
| home-after-maps | succeeded / system_button_acknowledged | 2687 | 9226 | `074025f4-af33-42b1-be2e-3a588c223b13` |
| calculator-return | failed / screen_changed | 1469 | 6330 | `78d8e69d-a87e-4465-a916-384856852871` |

Maps launch unknown outcome was resolved only by a fresh observation. Calculator-return failure was not replayed; last known foreground after that refused click remains launcher. Earlier same-version calculator result is retained as earlier evidence, not claimed as a successful final return.

## Residuals and future fix evidence

1. **App listing timeout (existing HOLD):** prior same-version request returned apps_query_timeout in18.5s wall time; not retried here. Source AndroidAppCatalog queries all visible launcher activities and loads every eligible label before applying the user filter. This is a candidate bottleneck, not a proven root cause. Record PackageManager query/label count and timings, dispatcher queue wait, timeout/cancellation latency; add stage-specific reasons. GenericControlOperations currently maps a timeout based on presence of input.filter, so a filtered screen observation can be mislabeled apps_query_timeout. Separate this by operation identity.
2. **Screen-change refusals:** Gemini model-title taps twice and final launcher calculator-return refused before action. Transition snapshots can differ from later native-boundary validation; false-positive fingerprint rejection has not been distinguished from real UI movement. Future privacy-safe logging should expose changed dimensions (window/geometry/tree/content-generation), capture-to-action age and post-observation read/wakeup count without screen text. Preserve full fresh security checks and never silently replay unknown effects.
3. **Transition observation cost:** Home totals1.789–2.687s, folder opening3.325s; post-observation reached2.266s. The1,600ms post-observer budget bounds scheduling, not slow Binder/probe runtime. Record capture/probe/traversal/Binder waits separately. Per-action submit/get wall6.188–9.286s remains materially above device totals.
4. **Transport outage:** two consecutive20.4s timeouts followed by normal status and operations. Existing evidence cannot locate the fault among tunnel/device/network. Future correlation should include redacted request/task ID, queue/start/end timestamps and session health without requiring reconnection.
5. **Samsung Internet content_missing:** retain node/text/WebView counts, OCR attempt and screenshot success. Reproduce on a known public page before diagnosing a general browser regression; do not label screenshot success as body-read success.
6. **Read diagnostics consistency / observability gap:** initial registered Gemini read reports identity_verified=false alongside a successful native-conversation read. Record which snapshot each identity metric refers to. Current diagnostics API has browser/Gemini/Tailscale events, but no generic observer or indicator attach/detach lifecycle trace. Add bounded redacted lifecycle telemetry (attempt/attached/failure/removed/reason) and operation ownership before trying to close the visual/lifecycle acceptance gap.

## Evidence storage and privacy

[Machine-readable redacted logs](evidence/v0166-no-camera-20261020.json) preserve38 current records,15 generic tasks, the earlier4 calculator actions, the question/task result,6 persisted question-stage events,5 browser diagnostic events, settings apply/rollback and independent readback, status and Termux metadata. Screen strings, node text/descriptions, raw screenshots, account/address data, shell stdout and tunnel URL/session identifier are removed. Per-node data is reduced to aggregate counts/lengths; native preflight fingerprint and raw app-private logs were not available. This limits root-cause diagnosis and is stated explicitly.

Build/test evidence remains the released version's CI:2,596 Android tests/0 failures/errors/skips; no rebuild needed for documentation/log capture. [Earlier implementation/release evidence](remote-control-feedback-latency.md).

No camera, note input, alarm mutation, message send, VPN reconfiguration, reconnect registration or APK install occurred. Only the synthetic Gemini test question was sent; runtime app access was temporarily expanded and exactly restored.
