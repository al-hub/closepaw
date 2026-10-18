# Generic controller startup correction v0.1.58

Status: RED regression in progress, not delivered. Installed baseline v0.1.57/code58.

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
