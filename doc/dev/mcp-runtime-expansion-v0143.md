# v0.1.43: Gemini foreground repair and MCP tool expansion

## Scope and evidence

v0.1.42/code43 reported four operations through eight unchanged MCP tools. Two real
Gemini draft tasks stopped during launch with `target_not_foreground`; neither
reached input. Existing logs do not identify the observed package, so the device's
exact failure cause is not proven. The uploaded diagnostic archive omitted Gemini
metadata entirely. Do not call real-device input verified until a new task succeeds.

## Changes

- Use the same foreground accessibility root for launch, composer selection,
  guarded ACTION_SET_TEXT and verification. Handle an active IME using only an
  active/focused application window; never choose a background Gemini window.
- Permit Google's host package only when a Gemini-specific editable composer is
  present. A generic Google search editor is rejected. Existing drafts, lock,
  ambiguous editors and incomplete trees remain blocked. Never click Send.
- Distinguish app_launch_failed, foreground_timeout, active_root_missing and
  gemini_host_unverified. Persist bounded package/window ID/type/active/focused
  metadata with task ID, stage, reason and timing. No prompt or UI text is added.
- Include gemini-draft-diagnostics.json in diagnostic ZIPs, including when Gemini
  events are the only available logs. Existing session trace privacy is unchanged.
- Add ninth read-only tool `get_runtime_info` with optional boolean
  `include_diagnostics` (default false), tool names/count and schema fingerprint.
  Keep all eight prior tool definitions exactly unchanged. Add server-side tool
  count/discovery hints to the existing get_status response.
- Keep existing endpoint, tunnel, pairing token and local switches. No plugin
  change is part of installation. Tools list remains static for one app build;
  listChanged notifications are not advertised or fabricated.

## Verification gates

CI must pass full Android unit tests, bridge tests and debug APK compilation before
main integration. Signed release verifies certificate continuity and asset hash.
Unit tests cover 8-to-9 tool expansion, unchanged old definitions, new optional
input/default/invalid types, additive output, fingerprint, no operation dispatch,
host rejection and persisted window metadata. Existing draft guards remain tested.

## Device/client acceptance after APK update

1. Record old plugin identity, version/code, available tools (8), operations (4).
2. Install signed v0.1.43/code44 over v0.1.42; no clear-data, uninstall, plugin
   re-registration, token edit or manual schema refresh. Reconnect saved service
   only if package update stopped it; record this separately.
3. Call existing get_status: server reports code44 and mcp_tool_count=9.
4. Inspect client tool availability and invoke get_runtime_info without changing
   plugin configuration. Test default input and include_diagnostics=true.
   Server tools/list=9 alone does NOT prove the client discovered the ninth tool.
5. If the tool is missing, record whether a new conversation discovers it while
   keeping the same plugin; do not silently refresh/re-register and claim success.
   Internal operation expansion remains separately accessible through existing tools.
6. Unlock, enable local Gemini draft permission, keep an empty composer and execute
   gemini.draft.insert once. Inspect confirmed task, verified=true, no send action.
7. Try a separate request with an existing draft: it must preserve it and fail.
   Check permission/lock refusal and Google Search non-target behavior.
8. Export ZIP: Gemini events must include the task and package/window observations.
9. Record server publication, client discovery, optional-input/additive-output
   behavior and actual device write as distinct pass/fail/pending results.

Breaking changes to an existing tool's required input are outside this release:
they would invalidate existing callers. This release probes optional input on a new
tool and additive output on old tools without claiming arbitrary schema migration.
