# Generic control device acceptance and read identity — v0.1.61 candidate

Status: regression-first correction in progress. Installed baseline0.1.60/code61; no new release claimed.

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
