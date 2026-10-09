package ai.closepaw.chatgpt

import ai.closepaw.app.AgentService
import ai.closepaw.perception.PerceptionConfig
import ai.closepaw.platform.AccessibilityPlatform
import ai.closepaw.platform.AccessibilityScreenshotCapturer
import ai.closepaw.platform.ActionResult
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.trace.NoopTraceRecorder
import ai.closepaw.util.recycleCompat
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class ReadAppToolResult(
    val structured: JsonObject,
    val imageBase64: String? = null,
    val imageMimeType: String? = null,
)

internal fun interface ReadAppTool {
    fun read(app: String): ReadAppToolResult
}

/**
 * Minimal browser-read path.
 *
 * One implementation for supported browsers:
 * 1) launch + verify foreground,
 * 2) collect Accessibility text,
 * 3) capture one bounded screenshot for visual fallback.
 *
 * No Shizuku, ADB or CDP is required by this path.
 */
internal class AndroidReadAppTool(
    private val serviceProvider: () -> AgentService? = { AgentService.instance },
    private val settleDelayMs: Long = DEFAULT_SETTLE_DELAY_MS,
) : ReadAppTool {

    override fun read(app: String): ReadAppToolResult = runBlocking(Dispatchers.Default) {
        val target = BrowserReadTarget.from(app)
            ?: return@runBlocking failure(
                code = "unsupported_app",
                summary = "read_app supports Samsung Internet and Chrome.",
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

        val alreadyForeground = platform.getCurrentPackageName() == target.packageName
        if (!alreadyForeground) {
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
        }

        val foregroundPackage = platform.getCurrentPackageName()
        if (foregroundPackage != target.packageName) {
            return@runBlocking failure(
                code = "target_not_foreground",
                summary = "${target.displayName} did not become the foreground app.",
                packageName = foregroundPackage,
            )
        }

        val accessibility = withContext(Dispatchers.Main) {
            readTargetApplicationText(service, target.packageName)
        }
        val targetText = accessibility.text
        val snapshot = if (targetText.isBlank()) platform.captureScreen() else null
        val fallbackText = snapshot?.elements
            ?.asSequence()
            ?.flatMap { element -> sequenceOf(element.text, element.description) }
            ?.map { value -> value.trim() }
            ?.filter { value -> value.isNotBlank() && value != "[password]" }
            ?.distinct()
            ?.joinToString("\n")
            .orEmpty()

        val rawContent = targetText.ifBlank { fallbackText }
        val content = rawContent.take(MAX_CONTENT_CHARS)
        val truncated = rawContent.length > MAX_CONTENT_CHARS

        val screenshotConfig = SessionConfig(
            perceptionConfig = PerceptionConfig.ScreenshotOnly(
                maxDimension = SCREENSHOT_MAX_DIMENSION,
                jpegQuality = SCREENSHOT_JPEG_QUALITY,
            )
        )
        val screenshotCapturer = AccessibilityScreenshotCapturer(
            service = service,
            config = screenshotConfig,
            traceRecorder = NoopTraceRecorder,
        )
        var screenshot = screenshotCapturer.captureIfEnabled(
            windowId = null,
            enabled = true,
        )
        var screenshotAttempts = 1

        // Samsung Internet can briefly expose browser chrome before the WebView surface settles.
        // Keep the retry bounded and cheap: one additional capture only.
        if (target.id == "samsung_internet") {
            delay(SAMSUNG_SCREENSHOT_RETRY_DELAY_MS)
            screenshot = screenshotCapturer.captureIfEnabled(
                windowId = null,
                enabled = true,
            ) ?: screenshot
            screenshotAttempts = 2
        }

        val image = screenshot?.image
        val readQuality = BrowserReadQuality.classify(content, image != null)
        val requestId = BrowserReadDiagnostics.record(
            target.id, readQuality, content.length, image != null,
            nodeCount = accessibility.nodeCount,
            textNodeCount = accessibility.textNodeCount,
            webViewCount = accessibility.webViewCount,
            rootAvailable = accessibility.rootAvailable,
            screenshotAttempts = screenshotAttempts,
        )
        val structured = buildJsonObject {
            put("status", if (content.isBlank() && image == null) "empty" else "succeeded")
            put(
                "summary",
                when {
                    readQuality == "content_missing" && image != null ->
                        "Captured browser screenshot, but Accessibility returned no meaningful page text."
                    content.isNotBlank() && image != null ->
                        "Read browser Accessibility text and captured the visible screen."
                    image != null ->
                        "Captured the visible browser screen for visual reading."
                    content.isNotBlank() ->
                        "Read visible browser Accessibility text."
                    else ->
                        "Browser is open, but no readable text or screenshot was available."
                }
            )
            put("request_id", requestId)
            put("text_read_status", readQuality)
            put("reason_code", readQuality)
            put("accessibility_root_available", accessibility.rootAvailable)
            put("accessibility_node_count", accessibility.nodeCount)
            put("accessibility_text_node_count", accessibility.textNodeCount)
            put("accessibility_webview_count", accessibility.webViewCount)
            put("app", target.id)
            put("package_name", target.packageName)
            put("scope", "visible_screen")
            put("content", content)
            put(
                "capture_source",
                when {
                    content.isNotBlank() && image != null -> "accessibility_plus_screenshot"
                    image != null -> "screenshot"
                    content.isNotBlank() -> "accessibility"
                    else -> "none"
                }
            )
            put("screenshot_attached", image != null)
            put("app_was_already_foreground", alreadyForeground)
            put("screenshot_attempts", screenshotAttempts)
            image?.let {
                put("screenshot_width", it.width)
                put("screenshot_height", it.height)
                put("screenshot_mime_type", it.mimeType)
            }
            put("truncated", truncated)
        }

        ReadAppToolResult(
            structured = structured,
            imageBase64 = image?.bytes?.let { bytes ->
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            },
            imageMimeType = image?.mimeType,
        )
    }

    private data class AccessibilityRead(
        val text: String,
        val rootAvailable: Boolean,
        val nodeCount: Int,
        val textNodeCount: Int,
        val webViewCount: Int,
    )

    private fun readTargetApplicationText(
        service: AgentService,
        packageName: String,
    ): AccessibilityRead {
        val windows = runCatching { service.windows }.getOrNull()
        var root: AccessibilityNodeInfo? = null
        try {
            if (!windows.isNullOrEmpty()) {
                val candidates = windows
                    .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .sortedByDescending { it.layer }
                for (window in candidates) {
                    val candidate = window.root ?: continue
                    if (candidate.packageName?.toString() == packageName) {
                        root = candidate
                        break
                    }
                    candidate.recycleCompat()
                }
            }

            if (root == null) {
                val activeRoot = service.rootInActiveWindow
                if (activeRoot?.packageName?.toString() == packageName) {
                    root = activeRoot
                } else {
                    activeRoot?.recycleCompat()
                }
            }

            val targetRoot = root ?: return AccessibilityRead("", false, 0, 0, 0)
            val values = LinkedHashSet<String>()
            var nodeCount = 0
            var textNodeCount = 0
            var webViewCount = 0

            fun visit(node: AccessibilityNodeInfo, recycle: Boolean) {
                try {
                    nodeCount++
                    if (node.className?.toString()?.contains("WebView", ignoreCase = true) == true) webViewCount++
                    if (node.isVisibleToUser) {
                        sequenceOf(
                            node.text?.toString(),
                            node.contentDescription?.toString(),
                            node.hintText?.toString(),
                        )
                            .filterNotNull()
                            .map { it.trim() }
                            .filter { it.isNotBlank() && it != "[password]" }
                            .forEach { value ->
                                textNodeCount++
                                values.add(value)
                            }
                    }
                    for (index in 0 until node.childCount) {
                        val child = node.getChild(index) ?: continue
                        visit(child, recycle = true)
                    }
                } finally {
                    if (recycle) node.recycleCompat()
                }
            }

            visit(targetRoot, recycle = false)
            return AccessibilityRead(values.joinToString("\n"), true, nodeCount, textNodeCount, webViewCount)
        } finally {
            root?.recycleCompat()
            windows?.forEach { window -> runCatching { window.recycle() } }
        }
    }

    private fun failure(
        code: String,
        summary: String,
        packageName: String? = null,
    ): ReadAppToolResult = ReadAppToolResult(
        structured = buildJsonObject {
            put("request_id", BrowserReadDiagnostics.record("unknown", code, 0, false))
            put("reason_code", code)
            put("status", "failed")
            put("error", code)
            put("summary", summary)
            packageName?.let { put("package_name", it) }
        }
    )

    private data class BrowserReadTarget(
        val id: String,
        val packageName: String,
        val displayName: String,
    ) {
        companion object {
            fun from(raw: String): BrowserReadTarget? =
                when (raw.trim().lowercase().replace(' ', '_').replace('-', '_')) {
                    "samsung_internet", "samsung_browser", "samsunginternet" ->
                        BrowserReadTarget(
                            id = "samsung_internet",
                            packageName = SAMSUNG_INTERNET_PACKAGE,
                            displayName = "Samsung Internet",
                        )
                    "chrome", "google_chrome" ->
                        BrowserReadTarget(
                            id = "chrome",
                            packageName = CHROME_PACKAGE,
                            displayName = "Chrome",
                        )
                    else -> null
                }
        }
    }

    companion object {
        internal const val SAMSUNG_INTERNET_PACKAGE = "com.sec.android.app.sbrowser"
        internal const val CHROME_PACKAGE = "com.android.chrome"
        private const val DEFAULT_SETTLE_DELAY_MS = 1_000L
        private const val MAX_CONTENT_CHARS = 12_000
        private const val SCREENSHOT_MAX_DIMENSION = 1024
        private const val SCREENSHOT_JPEG_QUALITY = 70
        private const val SAMSUNG_SCREENSHOT_RETRY_DELAY_MS = 800L
    }
}
