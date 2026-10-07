package ai.closepaw.chatgpt

import java.util.UUID

/**
 * Capability-style path used by the P1 read-capable MCP endpoint.
 *
 * Quick Tunnel URLs are temporary already. Adding an unguessable per-service path keeps the new
 * screen-reading tool off the public /mcp path without introducing an OAuth stack before the MVP
 * proves useful. This is intentionally a P1 protection, not the long-term authentication design.
 */
internal object McpAccessPath {
    fun generate(): String =
        "/mcp/" + UUID.randomUUID().toString().replace("-", "")
}
