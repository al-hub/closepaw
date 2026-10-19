# Generic control device acceptance and read identity — v0.1.61 candidate

Status: v0.1.61/code62 DELIVERED and REMOTELY INSTALLED. Locked non-UI checks passed; new-version sidebar/model/Voice acceptance remains open.

## Actual v0.1.60 unlocked checks (2026-10-19 KST)

- Installed/current0.1.60/code61 confirmed. Complete generic current-screen observation succeeded; no lock refusal in this interval.
- Label lookup returned the exact launchable Gemini package. Generic open_app succeeded and its post-observation positively identified the hosted Gemini UI. This accepts search/select/launch without the registered-app fallback.
- The same common path found and launched Chrome; post-observation identified Chrome. No browser contents are published.
- Model-title native click was acknowledged once but returned acknowledged_no_visible_change/screen_changed=false. No expand/collapse action was advertised. No repeat click, coordinate fallback, model-name handler, selection or question send was attempted.
- Gemini registered screen read succeeded on the composer screen. Generic sidebar open and close changed the actual screen. Registered read on the open sidebar still failed target_not_foreground. This narrows but does not yet explain the runtime failure.
- Device action timing_ms totals: Gemini launch1,449ms; model-title click933ms; sidebar open1,054ms; sidebar close1,021ms; Chrome launch1,876ms. Task executor intervals include additional overhead. First execute RPC3,489ms and get_task RPC7,007ms were measured separately. These are current observations, not a matched before/after end-to-end or speech-latency comparison.

## Read identity investigation and contract

The read adapter currently requires the activity marker and pane marker to both be visible. A structural activity marker is an identity anchor, not a visible action target. Retain the active/focused application-root gate, complete bounded traversal, protected-input refusal, exact host package, exact activity marker in the same tree, and an exact VISIBLE Gemini pane marker. A hidden pane cannot identify a foreground Google Search screen. This is read identity only; composer/write/send authority remains unchanged.

The exact real-device cause is not yet exposed. Add bounded boolean/count identity diagnostics without raw text or arbitrary identifiers, so the next real read can distinguish missing activity/pane markers, incomplete traversal and protected input. Do not label the visibility hypothesis proven before device validation.

Regression tests first exercise a visible pane with a structural nonvisible activity marker and reject hidden-pane/search fixtures. Preserve existing default two-argument policy behavior for the generic adapter. Then run full CI/lint, signed delivery, public download verification and one remote update. Actual model-menu selection, known-locked new-version checks and Voice/P2 remain open.

RED evidence: Security CI39202253195/job117897527141 compiled and ran2,546 tests with exactly1 assertion failure in visibleSidebarDoesNotRequireItsStructuralActivityMarkerToBeVisible. The hidden-pane/Search rejection regression passed. This reproduces the policy limitation; the actual device cause remains unconfirmed.

GREEN: Security CI39202777037/job117899094485 succeeded; downloaded artifact12165195046 parsed independently:268 suites,2,546 tests,0 failures/errors/skips; lint0 errors/108 warnings/4 informational. Python Termux55 and ChatGPT bridge8, cloudflared build and debug APK passed. Signed Android probe39202777040/job117899094264 succeeded. PR #106 merged as e8c6a9da8f801887da0563269315a7bf2d7697e1; main CI39203887423 started. No new-version device acceptance claimed yet.

## Signed delivery and locked-device checks

- Main Security CI39203887423 and Signed Release39204521059/job117904345193 succeeded.
- Immutable source/tag: e8c6a9da8f801887da0563269315a7bf2d7697e1 / v0.1.61.
- Public APK: https://github.com/al-hub/closepaw/releases/download/v0.1.61/closepaw-aa-bridge.apk
- Size116,425,565 bytes; SHA-256 7111665c9bc35931fa8b9effa97dac63d97ca095d84323a997337a2c9ee0405e.
- Independent anonymous release-runner download matched the built APK, checksum and Release asset digest. ai.closepaw/version0.1.61/code62, both native runtimes and immutable source/tag were verified. Original signer SHA-256 d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba retained.
- Immediately before the single remote update request, generic observation returned device_locked. The update entered installing from0.1.60/code61, then a connection timeout occurred across replacement. A subsequent same-connection status query confirmed installed/current0.1.61/code62; remote-update consent and install-source permission remained true. No repeat installation or plugin re-registration.
- After installation, screen observation again returned device_locked. In that interval a single Termux id command succeeded, exit0, no timeout/unknown outcome; a subsequent screen observation still returned device_locked. Shell process duration55ms is not a UI or speech latency result.
- Latest release check returned up_to_date. Protected screen responses reported action_attempted=false/outcome_unknown=false, and no wake/unlock/UI bypass was attempted.
- Thus installation was requested while locked, and installed-state/reconnection and Termux execution were confirmed with lock observations before/after. Continuous physical lock state throughout every instant of package replacement was not separately instrumented.

## Next entry point

The phone relocked while CI/build ran. New-version sidebar read diagnosis must wait for an unlocked ordinary screen. First open Gemini through a freshly listed exact package, open its sidebar semantically and call the registered read once; inspect read_identity booleans/counts if it refuses. Do not claim the visibility hypothesis or real-device sidebar fix proved by fixtures/installation alone.

Model selection remains unaccepted: the v0.1.60 model-title click produced no visible picker and advertised no native expand/collapse. Do not replay it automatically. A physical user check of whether that same title opens a picker can distinguish an app/UI availability problem from an accessibility-action problem; only after actual choices exist obtain the requested model. No model-specific handler or coordinate fallback was added.

Keep historical question/35-item reply and Chrome-menu acceptance without rerunning unrelated tests. A matched before/after end-to-end latency comparison, actual requested model selection, new-version sidebar read, long Doze/reboot recovery and Voice/P2 acceptance remain open. Only release/test metadata and bounded action timings are recorded; no private screen text, account data, app inventory or shell identity output is published.
