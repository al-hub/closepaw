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
 * Minimal P1/P2 read path for Samsung Internet, Chrome and Gemini.
 * 1) launch and verify the allowlisted foreground (including positively identified hosted Gemini),
 * 2) collect visible Accessibility text, then
 * 3) capture one bounded screenshot for shared MCP image + local Korean OCR fallback.
 * No Shizuku, ADB, or Chrome CDP required.
 */
internal class AndroidReadAppTool(
    private val serviceProvider: () -> AgentService? = { AgentService.instance },
    private val settleDelayMs: Long = DEFAULT_SETTLE_DELAY_MS,
    private val screenshotOcr: BrowserScreenshotOcr = BundledKoreanScreenshotOcr(),
) : ReadAppTool {

    override fun read(app: String): ReadAppToolResult = runBlocking(Dispatchers.Default) {
        val target = BrowserReadTarget.from(app)
            ?: return@runBlocking failure(
                code = "unsupported_app",
                summary = "Unsupported app. Use get_status supported_read_apps to discover configured targets.",
            )

        val service = serviceProvider()
            ?: return@runBlocking failure(
                code = "accessibility_not_ready",
                summary = "ClosePaw Accessibility service is not connected.",
            )

        val controlSession = service.remoteControlSession
        controlSession.refusal(controlSession.id, true)?.let {
            return@runBlocking failure(code = it, summary = "User control has priority.")
        }
        controlSession.activateLegacy()
        val controlEpoch = controlSession.epoch
        fun controlAllowed() = controlSession.epoch == controlEpoch && controlSession.refusal(controlSession.id, true) == null
        if (!controlAllowed()) return@runBlocking failure(code = "control_session_blocked", summary = "User control has priority.")

        try {
        withContext(Dispatchers.Main) { service.showRemoteControlActivity() }
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

        // Inspection only: reuse the native/Google-host Gemini identity guard, never its write path.
        val geminiUi = if (target.id == "gemini") AndroidGeminiDraftUi(service) { false } else null
        suspend fun foreground(): ReadForeground? {
            if (!controlAllowed()) return null
            if (geminiUi == null) {
                val pkg = platform.getCurrentPackageName()
                return if (ReadAppForegroundPolicy.accepts(target, pkg, false)) ReadForeground(pkg!!) else null
            }
            if (geminiUi.isLocked()) return null
            val state = geminiUi.inspect()
            val verifiedGemini = state.foreground || geminiUi.isReadScreen()
            val evidence = geminiUi.evidence()
            val pkg = evidence.rootPackage
            // Never infer identity from a background window or a generic Google Search box.
            val authoritativeRoot = pkg == evidence.observedPackage ||
                (evidence.windowType == AccessibilityWindowInfo.TYPE_APPLICATION && evidence.active == true && evidence.focused == true)
            if (!authoritativeRoot || !ReadAppForegroundPolicy.accepts(
                    target, pkg, verifiedGemini && pkg == AndroidGeminiDraftUi.GOOGLE_HOST,
                )) return null
            return ReadForeground(pkg!!, evidence.windowId)
        }

        var verifiedForeground = foreground()
        val alreadyForeground = verifiedForeground != null
        if (!alreadyForeground) {
            when (val launch = withContext(Dispatchers.Main) {
                if (!controlAllowed()) ActionResult.Failure("control_session_changed") else platform.launchApp(target.packageName)
            }) {
                is ActionResult.Failure -> {
                    return@runBlocking failure(code = "launch_failed", summary = launch.reason)
                }
                else -> Unit
            }
            delay(settleDelayMs)
            verifiedForeground = foreground()
            // A package launch can hand off to the Google-hosted Gemini activity asynchronously.
            var attempts = 0
            while (verifiedForeground == null && attempts++ < FOREGROUND_RECHECKS) {
                delay(FOREGROUND_RECHECK_DELAY_MS)
                verifiedForeground = foreground()
            }
        }
        val readForeground = verifiedForeground ?: return@runBlocking failure(
            code = "target_not_foreground",
            summary = "${target.displayName} foreground identity could not be verified.",
            packageName = platform.getCurrentPackageName(),
            identityDiagnostics = geminiUi?.readIdentityDiagnostics,
        )

        if (!controlAllowed()) return@runBlocking failure(code = "control_session_blocked", summary = "User control has priority.")
        controlSession.feedback("접근성 정보 읽는 중")
        val accessibility = withContext(Dispatchers.Main) {
            readTargetApplicationText(service, readForeground.packageName, readForeground.windowId)
        }
        val targetText = accessibility.text

        // P2 shares P1's single-snapshot semantics across all allowlisted apps.
        // Do not use platform.captureScreen(): that would capture a second, different image.
        val isGemini = target.id == "gemini"
        val needsReadableScreenshot = if (isGemini) {
            GeminiScreenReadPolicy.assess(targetText, hasScreenshot = false).shouldOcr
        } else {
            BrowserReadQuality.classify(targetText, false) != "ok"
        }
        // Allow composited UI content to settle before the single JPEG capture.
        delay(SCREEN_SETTLE_DELAY_MS)
        if (foreground() != readForeground) return@runBlocking failure(
            code = "target_changed", summary = "Target foreground changed before screen capture.",
        )
        val screenshotConfig = SessionConfig(
            perceptionConfig = PerceptionConfig.ScreenshotOnly(
                // Screenshot OCR needs legible Korean body text; keep regular reads small.
                maxDimension = if (needsReadableScreenshot) OCR_SCREENSHOT_MAX_DIMENSION
                    else SCREENSHOT_MAX_DIMENSION,
                jpegQuality = SCREENSHOT_JPEG_QUALITY,
            )
        )
        val screenshotCapturer = AccessibilityScreenshotCapturer(
            service = service,
            config = screenshotConfig,
            traceRecorder = NoopTraceRecorder,
        )
        controlSession.feedback("화면 캡처 중")
        val screenshot = screenshotCapturer.captureIfEnabled(
            windowId = null,
            enabled = true,
        )

        // Drop both text and image if focus changed while Android captured the screenshot.
        if (foreground() != readForeground) return@runBlocking failure(
            code = "target_changed", summary = "Target foreground changed during screen capture.",
            screenshotAttempts = 1,
        )
        val image = screenshot?.image
        val accessibilityContent = targetText
        val accessibilityQuality = if (isGemini) {
            GeminiScreenReadPolicy.assess(accessibilityContent, hasScreenshot = image != null).readQuality
        } else {
            BrowserReadQuality.classify(accessibilityContent, image != null)
        }
        val shouldOcr = accessibilityQuality != "ok" && image != null
        val ocrStarted = android.os.SystemClock.elapsedRealtime()
        val ocrContent = if (shouldOcr) {
            // Text first, image second: Voice MCP clients may not consume image blocks.
            // OCR is local, bounded, and only used when Accessibility misses page text.
            screenshotOcr.read(image!!.bytes)
        } else ""
        ai.closepaw.chatgpt.control.ControlDiagnostics.record("browser_ocr",
            if (!shouldOcr) "not_needed" else if (ocrContent.isBlank()) "no_usable_body" else "body_collected",
            android.os.SystemClock.elapsedRealtime() - ocrStarted, metrics = buildJsonObject {
                put("ocr_attempted", shouldOcr); put("ocr_chars", ocrContent.length)
                put("accessibility_chars", accessibilityContent.length); put("screenshot_available", image != null)
            })
        val rawContent = ocrContent.ifBlank { accessibilityContent }
        val content = rawContent.take(MAX_CONTENT_CHARS)
        val truncated = rawContent.length > MAX_CONTENT_CHARS
        val geminiAssessment = if (isGemini) {
            GeminiScreenReadPolicy.assess(content, hasScreenshot = image != null)
        } else null
        val readQuality = geminiAssessment?.readQuality
            ?: BrowserReadQuality.classify(content, image != null)
        val pageContent = if (geminiAssessment != null) {
            GeminiScreenReadPolicy.forModel(
                geminiAssessment,
                if (readQuality == "ok") content else "",
            )
        } else {
            BrowserReadQuality.pageContent(content, readQuality)
        }
        val fromOcr = ocrContent.isNotBlank() && readQuality == "ok"
        val requestId = BrowserReadDiagnostics.record(
            target.id, readQuality, content.length, image != null,
            nodeCount = accessibility.nodeCount,
            textNodeCount = accessibility.textNodeCount,
            webViewCount = accessibility.webViewCount,
            rootAvailable = accessibility.rootAvailable,
            screenshotAttempts = 1,
        )
        val structured = buildJsonObject {
            geminiUi?.readIdentityDiagnostics?.let { put("read_identity", it) }
            put("status", if (readQuality == "empty") "empty" else "succeeded")
            put(
                "summary",
                when {
                    geminiAssessment?.screenKind == "model_selector" ->
                        "Gemini Android model selection menu visible; this is not an AI answer."
                    geminiAssessment?.screenKind == "empty_composer" ->
                        "Gemini Android composer visible; no conversation reply on screen."
                    geminiAssessment?.screenKind == "generating" ->
                        "Gemini Android response appears to be generating; do not claim completion."
                    geminiAssessment?.screenKind == "conversation" ->
                        "Read native Gemini Android screen; speaker/answer attribution unverified."
                    isGemini ->
                        "No confirmed Gemini answer in the visible native app screen."
                    fromOcr ->
                        "Read visible page text from on-device screenshot OCR, with screenshot attached."
                    readQuality == "content_missing" && image != null ->
                        "Captured browser screenshot, but Accessibility returned no meaningful page text."
                    readQuality == "ok" && image != null ->
                        "Read browser Accessibility text and captured the visible screen."
                    image != null ->
                        "Captured the visible browser screen for visual reading."
                    readQuality == "ok" ->
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
            geminiAssessment?.let {
                put("screen_kind", it.screenKind)
                put("reply_status", it.replyStatus)
            }
            put("package_name", readForeground.packageName)
            put("launch_package_name", target.packageName)
            put("scope", "visible_screen")
            put("content", pageContent)
            put("text_source", when {
                fromOcr -> "on_device_ocr"
                readQuality == "ok" -> "accessibility"
                else -> "none"
            })
            put("ocr_attempted", shouldOcr)
            put(
                "capture_source",
                when {
                    fromOcr -> "screenshot_ocr"
                    readQuality == "ok" && image != null -> "accessibility_plus_screenshot"
                    image != null -> "screenshot"
                    readQuality == "ok" -> "accessibility"
                    else -> "none"
                }
            )
            put("screenshot_attached", image != null)
            put("app_was_already_foreground", alreadyForeground)
            put("screenshot_attempts", 1)
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
        } finally {
            controlSession.feedback("화면 읽기 종료 · 결과 확인")
        }
    }

    private data class ReadForeground(val packageName: String, val windowId: Int? = null)

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
        windowId: Int? = null,
    ): AccessibilityRead {
        val windows = runCatching { service.windows }.getOrNull()
        var root: AccessibilityNodeInfo? = null
        try {
            if (!windows.isNullOrEmpty()) {
                val candidates = windows
                    .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (windowId == null || it.id == windowId) }
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
                if (activeRoot?.packageName?.toString() == packageName && (windowId == null || activeRoot.windowId == windowId)) {
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
                    BrowserReadTextPolicy.visibleText(
                        visible = node.isVisibleToUser,
                        password = node.isPassword,
                        text = node.text?.toString(),
                        description = node.contentDescription?.toString(),
                        hint = node.hintText?.toString(),
                    ).forEach { value ->
                        textNodeCount++
                        values.add(value)
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
        screenshotAttempts: Int = 0,
        identityDiagnostics: kotlinx.serialization.json.JsonObject? = null,
    ): ReadAppToolResult = ReadAppToolResult(
        structured = buildJsonObject {
            put("request_id", BrowserReadDiagnostics.record("unknown", code, 0, false,
                screenshotAttempts = screenshotAttempts))
            put("reason_code", code)
            put("status", "failed")
            put("error", code)
            put("summary", summary)
            put("screenshot_attempts", screenshotAttempts)
            packageName?.let { put("package_name", it) }
            identityDiagnostics?.let { put("read_identity", it) }
        }
    )

    companion object {
        private const val FOREGROUND_RECHECKS = 12
        private const val FOREGROUND_RECHECK_DELAY_MS = 150L
        private const val DEFAULT_SETTLE_DELAY_MS = 1_000L
        private const val MAX_CONTENT_CHARS = 12_000
        private const val SCREENSHOT_MAX_DIMENSION = 1024
        private const val OCR_SCREENSHOT_MAX_DIMENSION = 1536
        private const val SCREENSHOT_JPEG_QUALITY = 70
        private const val SCREEN_SETTLE_DELAY_MS = 800L
    }
}
