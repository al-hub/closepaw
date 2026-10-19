# Composerless Gemini navigation host — v0.1.62 candidate

Status: v0.1.62/code63 DELIVERED and INSTALLED; locked checks and actual unlocked sidebar/composer read ACCEPTED. Model selection and Voice/P2 remain pending.

## Direct device evidence (2026-10-19 KST)

After the user unlocked the phone, generic Gemini list/select/launch succeeded. Generic sidebar open/close changed the actual screen (device totals1,098ms/1,038ms).
Registered read on the open sidebar still returned target_not_foreground with:
activity_anchor_present=true, activity_anchor_visible=true, sidebar_pane_visible=false, chat_pane_visible=false, traversal_complete=true, visited_nodes176, max_depth33, protected_input=false, identity_verified=false.
The previous activity-anchor visibility hypothesis is ruled out for this sample. v0.1.61 is not recorded as a real sidebar-read fix.

After closing the sidebar, the existing text-free gemini.ui.inspect verified the current root and returned actual native structural metadata: the exact activity anchor and assistant_robin_side_nav_host_overlapping_v2 were both visible in the same active/focused application root. Its content and chat pane markers were visible on the composer screen. This confirms the shared navigation host identifier on the installed UI; host visibility on the open sidebar still requires validation after correction.

## Narrow correction

Recognize the exact native navigation host as another visible Gemini pane witness, paired with the exact structural activity anchor and exact Google host package. Retain active/focused application root, complete bounded traversal, protected-input checks and all downstream screenshot/app/security gates. Do not accept anchor-only, host-only, hidden-host, other-package or generic Search fixtures. Expose navigation_host_visible among text-free read diagnostics. No model-specific handler, coordinate action or question/write relaxation.

First add regression tests against unchanged implementation; then full CI/lint, signed release/public APK verification, one remote update and available device checks. Model selection is still unaccepted: the earlier model-title native click had no visible effect and exposed no expand/collapse; do not automatically repeat it. P2/Voice remains open. No private screen/account/conversation content is published.

RED evidence: Security CI39209129381/job117918368588 compiled and ran2,548 tests with exactly1 expected assertion failure: actualNavigationHostCanIdentifyTheComposerlessSidebar. Anchor-only/host-only/hidden-host/foreign-package rejection checks passed.


GREEN: Security CI39209772706/job117920349523 succeeded. Downloaded report artifact12167186881 parsed independently:268 suites,2,548 tests,0 failures/errors/skips; lint0 errors/108 warnings/4 informational. Python Termux55/ChatGPT bridge8, native cloudflared and debug APK passed. Signed Android probe39209772618/job117920349384 succeeded. PR #107 merged as efb4bb54ac3173c32bf49fa87feaf60dfe43a30e. Main CI39211074900 started. Actual open-sidebar host visibility/read acceptance remains pending; no new APK/device result claimed.

## Signed delivery and latest device checkpoint

- Main Security CI39211074900 and Signed Release39211675610/job117926250013 succeeded.
- Immutable source/tag: efb4bb54ac3173c32bf49fa87feaf60dfe43a30e / v0.1.62.
- Public APK: https://github.com/al-hub/closepaw/releases/download/v0.1.62/closepaw-aa-bridge.apk
- Size116,425,705 bytes; SHA-256 37f17d786b468273d9a7c908368ac0e638b184f7d5702c7c3bcf291f8d48eaf9. Independent anonymous release-runner download matched the built APK, checksum and Release asset digest. Manifest ai.closepaw/0.1.62/code63, both native runtimes and immutable source/tag verified.
- Original signer SHA-256 d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba retained.
- On the user's later continue instruction, actual installed baseline remained0.1.61/code62 and generic observation returned device_locked. Exactly one remote update request targeted0.1.62. Durable state progressed installing to installed/current0.1.62/code63 on the same connection; remote-update consent and install-source permission remained true. No repeat installation/re-pairing/plugin registration.
- Post-install observation returned device_locked. One Termux id task succeeded exit0, no timeout/unknown outcome; another observation still returned device_locked. Latest update check returned up_to_date. Shell duration69ms is not a UI/speech benchmark.
- Installation was requested while locked and installed-state/connection/Termux were confirmed between lock observations. Continuous physical lock state during every instant of package replacement was not separately instrumented.
- No v0.1.62 UI action/read/model selection was attempted while locked. Do not claim real sidebar success from the fixture, signed APK or installed-state checks.

## Pre-acceptance plan — completed portions recorded below

1. Observe the unlocked ordinary screen, list Gemini again (package selection cache resets across update), and open the exact package.
2. Open the sidebar semantically once; call registered app.screen.read once. Inspect read_identity.navigation_host_visible and the returned success/failure. Publish only boolean/count diagnostics, never screen/account/conversation contents.
3. Close the sidebar with its observed semantic close control. Model-title native click is not automatically replayed: its previous result was acknowledged_no_visible_change and no native expand/collapse was advertised. A user touch of that same model title is needed to establish whether the app currently offers a picker at all. Once actual choices are visible, obtain the requested model and verify selection before the dedicated question path.
4. Retain already confirmed generic Gemini/Chrome search/select/launch and historical question/35-item reply coverage without rerunning unrelated tests. P2/Voice and matched end-to-end/speech latency remain open.

Command submission and get_task can be issued sequentially within one orchestration call to avoid an unnecessary assistant turn. The confirmed task result still decides completion. This workflow adjustment is not a measured before/after latency claim.

## Actual unlocked acceptance (2026-10-19 17:18 KST)

The user unlocked the phone. Generic observation positively identified the ordinary chat client screen. Filtered app lookup returned the exact Gemini launch package; generic open_app succeeded and observed the hosted Gemini root (device total1,686ms). No registered-read launch fallback was needed.

Generic sidebar open succeeded with an observed screen change (total1,141ms). The subsequent registered app.screen.read(app=gemini) SUCCEEDED with text_read_status=ok/reason_code=ok, app_was_already_foreground=true and truncated=false. Actual read_identity: activity_anchor_present=true, activity_anchor_visible=true, sidebar_pane_visible=false, navigation_host_visible=true, chat_pane_visible=false, traversal_complete=true, visited_nodes176, max_depth33, protected_input=false, identity_verified=true. This directly accepts the visible navigation-host correction on the real composerless sidebar; the prior target_not_foreground failure is resolved for this verified screen/version.

A fresh generic observation selected the actual sidebar close control. Close succeeded with an observed change (total1,012ms), and a subsequent registered read SUCCEEDED on the normal104-node composer screen, text_read_status=ok, truncated=false and already foreground. Thus both sidebar and composer states were verified on v0.1.62.

A final fresh observation confirmed the model-title label remains present with expandable=false/collapsible=false. No new model click, long press, coordinate action, draft edit or question send was attempted. The previous acknowledged-no-change title click is not silently converted into model-selection success.

Next requires comparing a user's physical touch of the model title with the earlier native-action result. Establish whether the installed app offers a picker at all before further controller changes or requested-model selection. No additional build/release is needed for the accepted sidebar fix. Retain existing question/35-item reply and Chrome acceptance; actual model selection, matched end-to-end/speech latency and Voice/P2 remain open. These timings are device intervals, not a measured before/after user feedback improvement. No raw sidebar, account or conversation contents are published.
