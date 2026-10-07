# ChatGPT Browser Read MVP

Status: P1 ACTIVE  
Updated: 2026-10-07

## Goal

Validate the smallest useful Android app-read path:

```text
ChatGPT Voice/Text
→ ClosePaw MCP
→ Samsung Internet
→ visible Accessibility text
→ ChatGPT
```

The user-visible completion sentence is:

> 삼성 인터넷 현재 페이지 읽어줘.

P1 is not a general Android automation framework. It exists to answer whether this path is useful on a real phone.

## Scope

Included:

- Samsung Internet only
- launch through the existing Android platform
- visible Accessibility-tree text only
- no screenshot
- no typing, submit, delete, purchase, message send, or payment
- fail closed if the expected package is not foreground

Deferred until real use proves necessary:

- page scrolling and full-page collection
- Chrome and Gemini
- AppProfile registry
- WRITE abstraction
- persistent sessions
- stable tunnel/OAuth

## MCP contract

`read_app`

Input:

```json
{"app":"samsung_internet"}
```

Output includes:

- status
- summary
- app
- package_name
- scope = visible_screen
- content
- element_count
- truncated

The returned text is capped to keep the first MVP bounded.

## P1 endpoint protection

The previous status-only PoC safely exposed `/mcp` through a temporary Quick Tunnel.

P1 adds screen content, so the service now generates an unguessable per-service MCP path and displays the full endpoint in Settings. The public default `/mcp` path no longer reaches the read-capable server in this build.

This is intentionally minimal MVP protection. It does not replace the longer-term authenticated stable endpoint if the feature proves worth daily use.

## Validation status

- Security CI: PASS
- Signed P1 release: v0.1.18 published
- Target Galaxy: v0.1.18 installed — user-confirmed 2026-10-07
- ChatGPT custom MCP endpoint refresh: pending
- Real-device Text/Voice E2E: pending

## Post-install finding

After v0.1.18 installation, the existing private ChatGPT ClosePaw plugin still referenced the legacy Quick Tunnel `/mcp` URL. A `get_status()` call through that plugin failed.

This is consistent with the intended P1 endpoint-protection change: v0.1.18 uses the newly displayed protected MCP path instead of the old public `/mcp` path.

The next step is therefore not another APK build. It is to update the private ChatGPT plugin to the full MCP endpoint currently displayed by ClosePaw v0.1.18, then resume Text and Voice E2E.

## Validation order

1. CI/unit tests — done
2. Install build on the target Galaxy — done
3. Update the ChatGPT custom MCP endpoint to the newly displayed URL — next
4. Text: ask ClosePaw to read Samsung Internet
5. Voice: ask “삼성 인터넷 현재 페이지 읽어줘”
6. Judge accuracy, latency, and whether Voice survives foreground app launch
7. Add scroll/full-page collection only if those real tests need it
