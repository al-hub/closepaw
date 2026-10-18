# Generic controller startup correction v0.1.58

Status: RED reproduced; GREEN candidate prepared, not delivered. Installed baseline v0.1.57/code58.

2026-10-19 KST device evidence: the user enabled the independent generic control switch.
All three generic operations now report ready. Filtered launcher discovery returned the intended Gemini target.
First current-screen observation succeeded but reported truncated=true/actionable=false, with six returned nodes.
No node action, question or send was attempted from that incomplete observation. No real screen text or screenshot is stored here.

The installed implementation bounds depth32/nodes512/output50 and rejects ALL actions on truncation.
Thus a deeply nested starting screen can also prevent Home and an exact app launch, although those actions select no screen node.
The exact truncation limit responsible was not exposed in the old result; add explicit reason/count/depth metadata rather than inventing it.

Correction contract: Home and an exact package previously selected by apps.list may use a fresh partial observation.
They retain local consent, secure-window/app/auth policy, latest foreground app/window/fingerprint checks, expiry,
single-use IDs, durable request dedup and the shared UI lock. Back/click/long-press/type/scroll still require a complete tree.
No coordinate or alternate executor fallback is introduced. Publish separate node-action and navigation capabilities.
Bounded traversal may inspect deeper nested structures without increasing the512-node cap; surface truncation causes.

Four test-first cases reproduce blocked non-node navigation and missing capability metadata while guarding all node actions.
Before GREEN publication, observe actual failing assertions in CI. Then update both engine and native adapter, review,
run full tests/lint/build, merge and use the existing signed release/public-file verification and one remote update request.
Actual Home/launch, Gemini model-menu inspection/selection, one dedicated question/new reply, a second ordinary app and Voice acceptance remain pending.
Do not choose an unspecified model or record APK/update delivery as generic scenario acceptance. P2 remains ACTIVE.

## Additional device boundary

A separate existing dedicated Gemini READ did not establish foreground identity (observed launcher category).
The next generic observe returned secure_window. No protected screenshot, node action, bypass or question was attempted.
This is separate from the first partial ChatGPT observation. Re-test only from an unlocked ordinary screen after delivery;
do not infer that a secure-window failure is the traversal defect or clear the guard to make the test pass.

## Local implementation candidate

Engine and native action boundary share GenericControlActionPolicy; fresh app/window/fingerprint and refusal reason
precede any partial-navigation exception. Only home/open_app are non-node navigation; Back remains complete-tree only.
Traversal depth64 retains the512-node cap and50 output nodes. Results expose max_depth, visited_nodes,
truncation_reasons, node_actions_allowed and navigation_actions. Existing actionable remains the node-action flag.
The input envelope/schema and local consent are unchanged. v0.1.58/code59 is a local candidate, not installed yet.
Seven new tests are planned across core and shared native policy, including output-node budget truncation.

## RED evidence

Security CI39169664784 / job117799896218 compiled and ran2525 tests;3 failed by assertion:
Home on a partial observation was rejected, navigation_actions was absent, and a changed foreground was hidden
behind the early truncation refusal. Back/node-action partial rejection still passed. This was not a build/dependency failure.
RED run: https://github.com/al-hub/closepaw/actions/runs/39169664784
The GREEN candidate adds7 cases total over v0.1.57 (expected2528 tests), including the shared native policy and output-node cap.

## GREEN and main checkpoint

Downloaded GREEN PR reports:2528 tests,0 failures,0 errors,0 skipped; lint0 errors/108 warnings.
PR Security CI39170137043 and signed native-runtime probe39170137041 succeeded before merge.
PR #103 merged as `2b06fa860d0332d4ed33e4b14274dbbd03a76592`.
Main Security CI39170962046 succeeded and automatically started Signed Release39171432082.
No update to v0.1.58 has yet been claimed; installed baseline remains v0.1.57 until actual replacement is confirmed.

- PR: https://github.com/al-hub/closepaw/pull/103
- GREEN Security CI: https://github.com/al-hub/closepaw/actions/runs/39170137043
- Signed probe: https://github.com/al-hub/closepaw/actions/runs/39170137041
- Main CI: https://github.com/al-hub/closepaw/actions/runs/39170962046
- Signed Release: https://github.com/al-hub/closepaw/actions/runs/39171432082

## v0.1.58 delivery and actual startup follow-up

Signed Release39171432082 succeeded. Source/tag2b06fa860d0332d4ed33e4b14274dbbd03a76592;
public APK116,416,361 bytes, SHA-25662cebe8e0c5cad69124fd567bce0f2b2e450ec62fc098ea13a34404af2c4fe97.
Anonymous public checksum/package/version0.1.58/code59/original signer/native runtime/tag checks passed; GitHub asset digest agrees.
One remote update task243ac41c-6ff6-4819-9500-453c526a0e06 reached installed/current0.1.58/code59 after reconnect.
Generic consent remains ON and all3 operations ready.
Actual starting observations now report complete211/depth47 and180/depth46 nodes/depth metadata, so the old depth32 ceiling is no longer blocking them.
Two exact Gemini app-open tasks (b2171fec-6160-4ed3-a7f9-ea601188248f and216c6c10-c3f5-4087-bd3f-baffc6b65cce)
were rejected before any effect: screen_changed, action_attempted=false, outcome_unknown=false. The second used a fresh observation.
Both observations identify the same chat-client app/window; internal refusal metadata does not reveal the exact changed component.
Code inspection confirms the entire content fingerprint is also required for non-node navigation, so normal client UI refresh can cause this rejection.
No further identical attempt or question send was made. This is distinct from the repaired traversal cap and from the earlier secure-window boundary.
The generic real-device launch gate remains OPEN; v0.1.59 will distinguish non-node foreground leases from node-content freshness.
