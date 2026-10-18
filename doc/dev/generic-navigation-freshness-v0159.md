# Generic non-node freshness v0.1.59

Status: v0.1.59/code60 DELIVERED and REMOTELY INSTALLED. Actual generic device acceptance BLOCKED by device_locked; P2 ACTIVE. Installed baseline v0.1.58/code59. No new delivery claimed.

The earlier depth32 failure is corrected in v0.1.58: complete device observations reached depth47.
App open was still refused twice before execution with screen_changed. Code confirms that Home/app launch
unnecessarily depend on the changing chat-client message tree, although neither action targets those nodes.
The exact runtime changed component was not returned, so distinguish this design finding from directly observed metadata.

Contract: Home/exact previously selected package launch require fresh foreground app/window identity, current local consent,
secure-window/app/auth checks, expiry, single-use observation, shared lock and durable dedup. Ordinary content refresh
within that same allowed window is not a semantic target change for those two actions.
Back/click/long-press/type/scroll continue to require complete observation and exact content fingerprint.
Do not relax protected-window refusal, unknown outcome handling, selected package policy or any node-targeted freshness check.
Expose validation_scope to make the action boundary explicit; retain existing input schemas and endpoint.

Three added tests first reproduce global-navigation content-refresh refusal while retaining all node/Back hash checks.
Run RED before uploading implementation, then GREEN/full lint/build, review/merge/main CI/signed delivery/public verification
and exactly one remote update. Re-test app launch using fresh actual screen evidence and preserve all drafts/questions.
The user has not specified a target model; inspect actual choices before requesting a selection. P2 ACTIVE.

RED evidence: Security CI39172782002/job117809145663 ran2,531 tests with exactly2 assertion failures: globalNavigationIgnoresOnlyOrdinaryContentRefresh (expected null, actual screen_changed) and globalNavigationCanStartFromAnUpdatingClient. This reproduces the refusal in existing code; the node/Back content-check regression passed.

GREEN evidence: Security CI39173228041/job117810481606 succeeded. Its downloaded XML reports contain265 suites and2,531 tests,0 failures/errors/skips; lint0 errors/108 warnings/4 informational. Python bridges55+8 passed and debug APK built. Signed Android probe39173228040/job117810481678 succeeded. PR #104 merged as bf8bbeba6647495234a76be42130fb93078002c6; local source files match the merged tree. Main CI39174209365 is in progress. No v0.1.59 APK or installed-state result claimed yet.

## Delivery and installed-state confirmation (2026-10-19 KST)

- Main Security CI39174209365 passed. Signed Release39174729253/job117814953919 passed, including the independent anonymous public-download verification step.
- Immutable tag/source: v0.1.59 / bf8bbeba6647495234a76be42130fb93078002c6.
- Public APK: https://github.com/al-hub/closepaw/releases/download/v0.1.59/closepaw-aa-bridge.apk
- Size116,417,125 bytes; SHA-256 2c2ba127c98f7b8b54277db44de7e5c08cc396f5cbbd53c1b9a653f9d6325be6. Release asset digest matches the independently downloaded APK.
- Manifest ai.closepaw / version0.1.59 / code60; original signer SHA-256 d6be7285b96b9f67b50df6daaf55e5501aef35ab484c170e60dd993590695aba retained. Both bundled native runtimes and the immutable source tag were verified by the release runner.
- Remote update check reported available0.1.59. Exactly one install request entered installing from0.1.58/code59. After a temporary connection timeout during restart, the same connection reported installed_version=current_version0.1.59/current_code60; remote-update and install-source permissions remained enabled. No second install request was sent.
- All3 generic operations remained ready and input schema hashes stayed unchanged after installation.

## Actual UI acceptance boundary

The first v0.1.59 generic screen observation returned device_locked with action_attempted=false and outcome_unknown=false. Filtered app lookup returned no entries while locked; this does not establish that Gemini is absent. No v0.1.59 app launch, node action or Gemini send was attempted. Do not retry/bypass a protected screen. The preceding v0.1.58 two screen_changed refusals remain historical evidence, not a v0.1.59 failure result.

Next requires the user to unlock the phone and keep an ordinary foreground screen visible. Then verify exact Gemini package launch, inspect actual available model choices and obtain the user's target model, observe the selected result, send one bounded dedicated test question/new reply, and exercise a second ordinary app through the same common contract. Voice/P2 acceptance remains open. APK delivery and installed-state success do not close those scenarios.

Only test/build/release metadata is recorded here; no private screen text, app inventory, screenshots or Gemini conversation content is published.
