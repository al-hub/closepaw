# ClosePaw diagnostics / MCP inspection / System & Debug version (implementation checklist)

Status: **Specification only**. Do not merge until application code, tests, and real-device validation are completed.

## Reproduction baseline (v0.1.33, 2026-10-09)

- ChatGPT MCP `read_app(app="samsung_internet")` returns `status=succeeded`, `capture_source=accessibility_plus_screenshot`, `screenshot_attached=true`, and readable article screenshot.
- Accessibility content contains only `최근 앱\n홈\n뒤로가기`, without page body, despite accessibility service being enabled.
- This is screenshot success but text extraction failure. Do not conflate these.

## 1. Default structured logging

Instrument each existing `read_app` operation without altering the generic Accessibility + bounded screenshot architecture. Single `request_id` across ingress, foreground verification, accessibility traversal, filter/serialization, screenshot and response.

Required privacy-safe fields: UTC time, request_id, target/foreground package, service_connected, root_available, active_window_package, node_count, text_node_count, webview_node_count, raw/extracted/filtered character counts, capture_source, screenshot_attempts/attached, timings per stage, truncated, result/reason_code, APK version_name/version_code, git_sha if available.

Reason codes at minimum: `service_unavailable`, `root_unavailable`, `wrong_foreground`, `empty_tree`, `text_filtered_out`, `content_missing`, `capture_failed`, `ok`. A successful screenshot with navigation-only text must not count as successful text-read. Preserve response backward compatibility while adding an explicit warning/degraded-quality marker.

Default log storage is bounded and on-device. Never log raw screen content, URL query, tokens, cookies, full screenshots or login information. Reuse existing trace/diagnostics export plumbing.

## 2. MCP get_diagnostics

Add a read-only `get_diagnostics` MCP tool exposed through existing secure transport. Include build metadata, runtime/tunnel/local-MCP/Accessibility health, latest request ID, bounded recent sanitized `read_app` diagnostic summaries and failure counts. Enforce record/window/response size limits. Do not permit arbitrary log paths, raw image/text or mutation; follow existing access controls. Return machine-readable reason codes that ChatGPT can interpret.

## 3. System & Debug

Display BuildConfig derived `versionName`, `versionCode`, flavor and short git SHA (or "Unavailable"). Reuse these same values in `get_status` and `get_diagnostics`; avoid manual version strings. Prefer a diagnostics summary entry plus existing ZIP export, with a copyable sanitized summary.

## TDD acceptance matrix

| Case | Expected |
|---|---|
| Content nodes + screenshot present | text_read=ok, screenshot=ok |
| Screenshot present, only Android navigation labels | content_missing, not successful text extraction |
| rootInActiveWindow null | root_unavailable, no crash |
| Foreground package differs | wrong_foreground |
| WebView subtree unavailable | explicit node/text counts + reason |
| Sanitizer drops all body text | raw vs filtered counts reveal stage |
| Diagnostic buffer overflow | oldest records expire, bounded output |
| MCP diagnostics | read-only, sanitized, authorization respected |
| Build metadata | UI, get_status and get_diagnostics agree |
| Chrome and Samsung Internet | common generic path, no CDP/Shizuku/ADB |

## Implementation discipline

SRP/TDD: separate telemetry collection, privacy-safe bounded store, MCP DTO/tool, and UI presentation. Run `./gradlew clean assembleDebug lint test`, repository security workflows, then signed release workflow. Update PROGRESS.md with actual completion results only. Real Galaxy verification is required before closing P1 / shipping as done.

This document intentionally does not claim that implementation, tests or release have been completed.
