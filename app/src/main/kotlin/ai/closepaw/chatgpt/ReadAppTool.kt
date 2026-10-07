package ai.closepaw.chatgpt

import ai.closepaw.app.AgentService
import ai.closepaw.platform.AccessibilityPlatform
import ai.closepaw.platform.ActionResult
import ai.closepaw.protocol.SessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun interface ReadAppTool {
    fun read(app: String): JsonObject
}

/**
 * P1 browser-read MVP.
 *
 * Intentionally supports Samsung Internet only. It launches the target through the existing
 * Android platform, waits for the foreground transition, then returns visible Accessibility text.
 * It never types, submits, deletes, purchases, or captures a screenshot.
 */
internal class AndroidReadAppTool(
    private val serviceProvider: () -> AgentService? = { AgentService.instance },
    private val settleDelayMs: Long = DEFAULT_SETTLE_DELAY_MS,
) : ReadAppTool {

    override fun read(app: String): JsonObject = runBlocking(Dispatchers.Default) {
        val target = BrowserReadTarget.from(app)
            ?: return@runBlocking failure(
                code = "unsupported_app",
                summary = "P1 read_app currently supports Samsung Internet only.",
            )

        val service = serviceProvider()
            ?: return@runBlocking failure(
                code = "accessibility_not_ready",
                summary = "ClosePaw Accessibility service is not connected.",
            )

        val platform = AccessibilityPlatform(
            service = service,
            config = SessionConfig(),
        )

        if (!platform.hasRequiredPermissions()) {
            return@runBlocking failure(
                code = "accessibility_not_ready",
                summary = "ClosePaw Accessibility service is not ready.",
            )
        }

        when (val launch = platform.launchApp(target.packageName)) {
            is ActionResult.Failure -> {
                return@runBlocking failure(
                    code = "launch_failed",
                    summary = launch.reason,
                )
            }
            else -> Unit
        }

        delay(settleDelayMs)

        val foregroundPackage = platform.getCurrentPackageName()
        if (foregroundPackage != target.packageName) {
            return@runBlocking failure(
                code = "target_not_foreground",
                summary = "Samsung Internet did not become the foreground app.",
                packageName = foregroundPackage,
            )
        }

        val snapshot = platform.captureScreen()
        val rawContent = snapshot.elements
            .asSequence()
            .flatMap { element -> sequenceOf(element.text, element.description) }
            .map { text -> text.trim() }
            .filter { text -> text.isNotBlank() && text != "[password]" }
            .distinct()
            .joinToString("\n")

        val truncated = rawContent.length > MAX_CONTENT_CHARS
        val content = rawContent.take(MAX_CONTENT_CHARS)
        val status = if (content.isBlank()) "empty" else "succeeded"
        val summary = if (content.isBlank()) {
            "Samsung Internet is open, but no readable visible text was found."
        } else {
            "Read visible content from Samsung Internet."
        }

        buildJsonObject {
            put("status", status)
            put("summary", summary)
            put("app", target.id)
            put("package_name", target.packageName)
            put("scope", "visible_screen")
            put("content", content)
            put("element_count", snapshot.elements.size)
            put("truncated", truncated)
        }
    }

    private fun failure(
        code: String,
        summary: String,
        packageName: String? = null,
    ): JsonObject = buildJsonObject {
        put("status", "failed")
        put("error", code)
        put("summary", summary)
        packageName?.let { put("package_name", it) }
    }

    private data class BrowserReadTarget(
        val id: String,
        val packageName: String,
    ) {
        companion object {
            fun from(raw: String): BrowserReadTarget? =
                when (raw.trim().lowercase().replace(' ', '_').replace('-', '_')) {
                    "samsung_internet", "samsung_browser", "samsunginternet" ->
                        BrowserReadTarget(
                            id = "samsung_internet",
                            packageName = SAMSUNG_INTERNET_PACKAGE,
                        )
                    else -> null
                }
        }
    }

    companion object {
        internal const val SAMSUNG_INTERNET_PACKAGE = "com.sec.android.app.sbrowser"
        private const val DEFAULT_SETTLE_DELAY_MS = 1_000L
        private const val MAX_CONTENT_CHARS = 12_000
    }
}
