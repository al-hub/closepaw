package ai.closepaw.chatgpt

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class SecureTunnelDiagnosticsResult(
    val summary: String,
)

internal class SecureMcpTunnelDiagnostics(
    private val healthBaseUrl: String = "http://127.0.0.1:18425",
    private val mcpUrl: String = "http://127.0.0.1:18424/mcp",
) {
    fun run(): SecureTunnelDiagnosticsResult {
        val ready = getText("$healthBaseUrl/readyz")
        val controlPlane = getText("$healthBaseUrl/health/control-plane")
        val mcpHealth = getText("$healthBaseUrl/health/mcp")
        val status = getText("$healthBaseUrl/api/status", maxChars = 900)
        val logs = getText("$healthBaseUrl/api/logs?limit=80", maxChars = 1800)
        val initialize = postJson(
            mcpUrl,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"ClosePawSelfTest","version":"1"}}}""",
        )

        return SecureTunnelDiagnosticsResult(
            summary = buildString {
                appendLine("readyz: ${ready.label}")
                appendLine("control-plane: ${controlPlane.label}")
                appendLine("mcp-health: ${mcpHealth.label}")
                appendLine("admin-status: ${status.label}")
                appendLine("recent-logs: ${logs.label}")
                append("local-mcp-initialize: ${initialize.label}")
            }
        )
    }

    private fun getText(url: String, maxChars: Int = 360): ProbeResult = request("GET", url, null, maxChars)

    private fun postJson(url: String, body: String): ProbeResult = request("POST", url, body, 360)

    private fun request(method: String, url: String, body: String?, maxChars: Int): ProbeResult = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 1_500
            readTimeout = 2_500
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(StandardCharsets.UTF_8))
            }
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            ProbeResult("HTTP $code ${compact(text, maxChars)}")
        } finally {
            connection.disconnect()
        }
    }.getOrElse { error ->
        ProbeResult("ERROR ${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}")
    }

    private fun compact(value: String, maxChars: Int): String {
        if (value.isBlank()) return "(empty)"
        val redacted = value
            .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "sk-REDACTED")
            .replace(Regex("Bearer\\s+[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE), "Bearer REDACTED")
        return redacted.replace(Regex("\\s+"), " ").trim().take(maxChars)
    }

    private data class ProbeResult(val label: String)
}
