package ai.closepaw.chatgpt

import java.util.ArrayDeque

internal object SecureTunnelRuntimeLogBuffer {
    private const val MAX_LINES = 120
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun clear() {
        lines.clear()
    }

    @Synchronized
    fun append(line: String) {
        val sanitized = sanitize(line)
        if (sanitized.isBlank()) return
        lines.addLast(sanitized.take(1200))
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun recent(limit: Int = 80): String {
        if (lines.isEmpty()) return "(no runtime logs captured)"
        return lines.toList().takeLast(limit.coerceAtLeast(1)).joinToString("\n")
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "sk-REDACTED")
        .replace(Regex("Bearer\\s+[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE), "Bearer REDACTED")
}
