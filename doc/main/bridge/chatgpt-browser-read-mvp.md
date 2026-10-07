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

## P1 connection compatibility

P0 proved that ChatGPT Android Text/Voice can reach ClosePaw through a temporary Quick Tunnel using the standard `/mcp` endpoint.

v0.1.18 experimented with an unguessable `/mcp/<random>` path before exposing browser READ. The plugin was updated to the exact displayed endpoint, but both the existing conversation and a fresh ChatGPT conversation failed to connect.

For P1, the random-path protection is therefore rolled back as unnecessary compatibility risk. v0.1.19 restores the previously proven standard `/mcp` endpoint. This is acceptable only for the narrow P1 browser-read MVP while the ephemeral Quick Tunnel is explicitly running. Sensitive targets such as KakaoTalk and Toss remain deferred until a real authentication boundary is implemented.

## Validation status

- Security CI: PASS
- Security CI for v0.1.18 implementation: PASS
- v0.1.18 protected-path experiment: FAILED to connect in both existing and fresh ChatGPT conversations
- Conversation-cache hypothesis: rejected
- v0.1.19 standard-`/mcp` compatibility fix: CI PASS and signed release published
- Real-device Text/Voice browser-read E2E: pending

## Post-install findings

The private plugin was updated to the exact protected endpoint displayed by ClosePaw v0.1.18. `get_status()` still failed in the current conversation, and the user then reproduced the same failure in a new ChatGPT conversation.

That fresh-chat reproduction rules out the earlier cache/binding theory as the primary explanation. The simplest meaningful difference from the proven P0 path is the randomized MCP path introduced in v0.1.18.

P1 now follows the practical rule: restore the known-good standard `/mcp` shape first, verify browser READ, and defer stronger authentication until before sensitive-app milestones.

## Validation order

1. v0.1.19 standard-`/mcp` fix CI — done
2. Publish signed v0.1.19 — done
3. Install signed v0.1.19
4. Refresh the private plugin to the new Quick Tunnel `/mcp` URL
5. Retry `get_status()`
6. Text: ask ClosePaw to read Samsung Internet
7. Voice: ask “삼성 인터넷 현재 페이지 읽어줘”
8. Judge accuracy, latency, and whether Voice survives foreground app launch
9. Add scroll/full-page collection only if those real tests need it
