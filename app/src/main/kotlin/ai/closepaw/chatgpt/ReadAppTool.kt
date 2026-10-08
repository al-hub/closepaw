package ai.closepaw.chatgpt

import ai.closepaw.app.AgentService
import ai.closepaw.platform.AccessibilityPlatform
import ai.closepaw.platform.ActionResult
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.util.recycleCompat
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

        val targetTree = withContext(Dispatchers.Main) {
            readTargetApplicationTree(service, target.packageName)
        }
        val snapshot = if (targetTree.content.isBlank()) platform.captureScreen() else null
        val fallbackContent = snapshot?.elements
            ?.asSequence()
            ?.flatMap { element -> sequenceOf(element.text, element.description) }
            ?.map { text -> text.trim() }
            ?.filter { text -> text.isNotBlank() && text != "[password]" }
            ?.distinct()
            ?.joinToString("\n")
            .orEmpty()

        val rawContent = targetTree.content.ifBlank { fallbackContent }
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
            put("element_count", if (targetTree.content.isNotBlank()) targetTree.elementCount else snapshot?.elements?.size ?: 0)
            put("capture_source", if (targetTree.content.isNotBlank()) "target_application_tree" else "generic_snapshot_fallback")
            put("truncated", truncated)
        }
    }

    private data class TargetTreeRead(
        val content: String,
        val elementCount: Int,
    )

    private fun readTargetApplicationTree(
        service: AgentService,
        packageName: String,
    ): TargetTreeRead {
        val windows = runCatching { service.windows }.getOrNull()
        var root: AccessibilityNodeInfo? = null
        try {
            if (!windows.isNullOrEmpty()) {
                val candidates = windows
                    .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .sortedByDescending { it.layer }
                for (window in candidates) {
                    val candidate = window.root ?: continue
                    val candidatePackage = candidate.packageName?.toString()
                    if (candidatePackage == packageName) {
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

            val targetRoot = root ?: return TargetTreeRead("", 0)
            val values = LinkedHashSet<String>()
            var count = 0

            fun visit(node: AccessibilityNodeInfo, recycle: Boolean) {
                try {
                    if (node.isVisibleToUser) {
                        sequenceOf(
                            node.text?.toString(),
                            node.contentDescription?.toString(),
                            node.hintText?.toString(),
                        )
                            .filterNotNull()
                            .map { it.trim() }
                            .filter { it.isNotBlank() && it != "[password]" }
                            .forEach {
                                values.add(it)
                                count += 1
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
            return TargetTreeRead(values.joinToString("\n"), count)
        } finally {
            root?.recycleCompat()
            windows?.forEach { window ->
                runCatching { window.recycle() }
            }
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
