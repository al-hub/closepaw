package ai.closepaw.chatgpt

import ai.closepaw.app.AgentService
import ai.closepaw.browser.script.BrowserSessionManager
import ai.closepaw.browser.script.ScriptResult
import ai.closepaw.trace.NoopTraceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class ChromeCdpReadAdapter(
    private val maxContentChars: Int = 12_000,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(service: AgentService): JsonObject {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = BrowserSessionManager(
            context = service.applicationContext,
            sessionScope = scope,
            traceRecorder = NoopTraceRecorder,
        )
        return try {
            val result = manager.run(READ_VISIBLE_PAGE_SCRIPT, 30_000L)
            when (result) {
                is ScriptResult.Ok -> success(result.resultJson)
                is ScriptResult.Failure -> failure("cdp_script_failed", result.message)
                is ScriptResult.Timeout -> failure(
                    "cdp_timeout",
                    "Chrome CDP read timed out after ${result.timeoutMs} ms.",
                )
                is ScriptResult.Cancelled -> failure("cdp_cancelled", result.reason)
                is ScriptResult.HostError -> failure(
                    "cdp_host_error",
                    result.cause.message ?: result.cause::class.java.simpleName,
                )
            }
        } catch (t: Throwable) {
            failure(
                "cdp_unavailable",
                t.message ?: t::class.java.simpleName,
            )
        } finally {
            manager.close()
            scope.cancel()
        }
    }

    private fun success(resultJson: String?): JsonObject {
        val payload = runCatching {
            resultJson?.let { json.parseToJsonElement(it).jsonObject }
        }.getOrNull()

        if (payload == null) {
            return failure("cdp_invalid_result", "Chrome CDP returned an invalid result.")
        }

        val url = payload["url"]?.jsonPrimitive?.content.orEmpty()
        val title = payload["title"]?.jsonPrimitive?.content.orEmpty()
        val text = payload["text"]?.jsonPrimitive?.content.orEmpty()
        val content = text.take(maxContentChars)
        val truncated = text.length > maxContentChars

        return buildJsonObject {
            put("status", if (content.isBlank()) "empty" else "succeeded")
            put(
                "summary",
                if (content.isBlank()) {
                    "Chrome is connected through CDP, but the current page has no readable body text."
                } else {
                    "Read Chrome page content through CDP."
                }
            )
            put("app", "chrome")
            put("package_name", CHROME_PACKAGE)
            put("scope", "current_page_dom")
            put("capture_source", "chrome_cdp_runtime_evaluate")
            put("url", url)
            put("title", title)
            put("content", content)
            put("truncated", truncated)
        }
    }

    private fun failure(code: String, summary: String): JsonObject = buildJsonObject {
        put("status", "failed")
        put("error", code)
        put("summary", summary)
        put("app", "chrome")
        put("package_name", CHROME_PACKAGE)
    }

    companion object {
        const val CHROME_PACKAGE = "com.android.chrome"

        private val READ_VISIBLE_PAGE_SCRIPT = """
            const response = await cdp("Runtime.evaluate", {
              expression: "({url: location.href, title: document.title || '', text: (document.body && document.body.innerText) ? document.body.innerText : ''})",
              returnByValue: true,
              awaitPromise: true
            });
            const remote = response && response.result ? response.result : {};
            return remote.value || { url: "", title: "", text: "" };
        """.trimIndent()
    }
}
