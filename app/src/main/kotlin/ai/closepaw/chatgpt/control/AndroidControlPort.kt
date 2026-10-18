package ai.closepaw.chatgpt.control

import ai.closepaw.app.AgentService
import ai.closepaw.chatgpt.AndroidGeminiDraftUi
import ai.closepaw.chatgpt.GeminiDraftComposerPolicy
import ai.closepaw.chatgpt.operations.operationHash
import ai.closepaw.platform.AccessibilityPlatform
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.SystemButtonType
import ai.closepaw.platform.UIAction
import ai.closepaw.protocol.AppTier
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.protocol.SessionState
import ai.closepaw.tool.AppClassifierHolder
import ai.closepaw.util.recycleCompat
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

/** Native adapter: no app-specific planner, coordinates, gesture fallback, screen logging or persistent node refs. */
internal class AndroidControlPort(context: Context, private val mutationBlock: (String) -> String? = { null },
    private val allowed: () -> Boolean) : ControlPort {
    private val context = context.applicationContext
    private val classifier = AppClassifierHolder.get(this.context)
    private var listedPackages = emptySet<String>()
    private var certifiedWindow = -1
    private fun reason(service: AgentService?): String? = when {
        !allowed() -> "generic_control_disabled"
        service == null -> "accessibility_permission_missing"
        AgentService.instance !== service -> "accessibility_service_changed"
        context.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !context.getSystemService(PowerManager::class.java).isInteractive -> "device_locked"
        service.getActiveSession()?.state?.value in setOf(SessionState.Running, SessionState.TakeoverPending) -> "local_agent_busy"
        else -> null
    }
    private fun denied(code: String) = ControlFrame("", -1, "", emptyList(), reason = code)
    private fun platform(service: AgentService) = AccessibilityPlatform(service, SessionConfig(),
        isPackageBlocked = { classifier.classify(it) == AppTier.BLOCKED })

    override suspend fun apps(filter: String): JsonArray {
        val service = AgentService.instance
        if (reason(service) != null || service == null) { listedPackages = emptySet(); return JsonArray(emptyList()) }
        val matches = platform(service).getInstalledApps().filter {
            (it.label.contains(filter, true) || it.packageName.contains(filter, true)) && packageAllowed(it.packageName)
        }.sortedWith(compareBy({ it.label }, { it.packageName })).take(40)
        if (reason(service) != null) { listedPackages = emptySet(); return JsonArray(emptyList()) }
        listedPackages = matches.map { it.packageName }.toSet()
        return JsonArray(matches.map { buildJsonObject { put("label", it.label.take(128)); put("package_name", it.packageName) } })
    }
    private fun packageAllowed(pkg: String): Boolean {
        if (pkg == context.packageName || pkg.contains("permissioncontroller") || pkg.contains("packageinstaller") ||
            pkg == "com.android.systemui") return false
        if (classifier.classify(pkg) == AppTier.BLOCKED) return false
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcher = context.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return classifier.classify(pkg) == AppTier.NORMAL || pkg == launcher
    }
    override suspend fun capture(): ControlFrame {
        val service = AgentService.instance
        reason(service)?.let { return denied(it) }
        service ?: return denied("accessibility_permission_missing")
        // Unlike a general accessibility tree, this signal can reject FLAG_SECURE windows.
        // On older Android the adapter declines to claim a secure-window check it cannot perform.
        secureWindow(service)?.let { return denied(it) }
        return withTree(service) { frame, _ -> frame }
    }
    private suspend fun secureWindow(service: AgentService): String? {
        certifiedWindow = -1
        if (Build.VERSION.SDK_INT < 34) return "secure_window_probe_requires_android14"
        delay(400) // respect Android screenshot request interval; no fallback on a failed probe
        return withContext(Dispatchers.Main) {
            reason(service)?.let { return@withContext it }
            val root = service.rootInActiveWindow ?: return@withContext "active_root_missing"
            val id = root.windowId
            root.recycleCompat()
            suspendCancellableCoroutine<String?> { continuation ->
                try {
                    service.takeScreenshotOfWindow(id, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            screenshot.hardwareBuffer.close()
                            if (continuation.isActive) { certifiedWindow = id; continuation.resume(null) }
                        }
                        override fun onFailure(errorCode: Int) {
                            if (continuation.isActive) continuation.resume(if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
                                "secure_window" else "secure_window_probe_failed")
                        }
                    })
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume("secure_window_probe_failed")
                }
            }
        }
    }
    private suspend fun <T> withTree(service: AgentService,
        block: suspend (ControlFrame, List<AccessibilityNodeInfo>) -> T): T = withContext(Dispatchers.Main) {
        reason(service)?.let { return@withContext block(denied(it), emptyList()) }
        val root = service.rootInActiveWindow ?: return@withContext block(denied("active_root_missing"), emptyList())
        val owned = mutableListOf(root)
        val windows = service.windows.orEmpty()
        try {
            if (root.windowId != certifiedWindow) return@withContext block(denied("secure_window_changed"), emptyList())
            val window = windows.singleOrNull { it.id == root.windowId }
            if (window == null || window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isActive || !window.isFocused)
                return@withContext block(denied("foreground_application_required"), emptyList())
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank()) return@withContext block(denied("package_unknown"), emptyList())
            val nodes = mutableListOf<ControlNode>()
            val native = mutableListOf<AccessibilityNodeInfo>()
            val identity = StringBuilder()
            var truncated = false
            var protected = false
            var gemini = false
            var visited = 0
            fun visit(node: AccessibilityNodeInfo, depth: Int, parent: Int) {
                if (depth > 32 || visited >= 512) { truncated = true; return }
                val index = visited++
                val rect = Rect(); node.getBoundsInScreen(rect)
                val text = node.text?.toString().orEmpty()
                val description = node.contentDescription?.toString().orEmpty()
                val resource = node.viewIdResourceName.orEmpty()
                val hint = node.hintText?.toString().orEmpty()
                val sensitiveId = resource.substringAfterLast('/').lowercase()
                if (node.isPassword || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) ||
                    listOf("password", "credential", "biometric", "lock_settings", "pin_entry", "otp", "permission_allow").any { sensitiveId.contains(it) }) protected = true
                if (GeminiDraftComposerPolicy.isComposer(hint, description, resource.substringAfterLast('/'), true) && node.isEditable) gemini = true
                val json = buildJsonObject {
                    put("id", index); put("parent", parent); put("pkg", node.packageName?.toString().orEmpty())
                    put("text_hash", operationHash(text)); put("description_hash", operationHash(description)); put("hint", operationHash(hint))
                    put("resource", resource); put("class", node.className?.toString().orEmpty()); put("bounds", rect.flattenToString())
                    put("visible", node.isVisibleToUser); put("enabled", node.isEnabled); put("clickable", node.isClickable)
                    put("long_clickable", node.isLongClickable); put("editable", node.isEditable); put("scrollable", node.isScrollable); put("password", node.isPassword)
                    put("child_count", node.childCount); put("actions", node.actionList.joinToString { it.id.toString() })
                }
                identity.append(json.toString()).append('\n')
                if (node.isVisibleToUser && !rect.isEmpty &&
                    (text.isNotEmpty() || description.isNotEmpty() || node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable ||
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) {
                    if (nodes.size >= 50) truncated = true
                    else {
                        nodes.add(ControlNode(index, if (node.isShowingHintText) "" else text.take(2001), description.take(100), resource.take(100),
                            node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }, node.isEditable, node.isScrollable,
                            node.isEnabled, node.isPassword, node.isShowingHintText || text.isEmpty(), rect.flattenToString(), parent, node.isLongClickable))
                        native.add(node)
                    }
                }
                for (i in 0 until node.childCount) {
                    if (visited >= 512) { truncated = true; break }
                    val child = node.getChild(i) ?: continue
                    owned.add(child); visit(child, depth + 1, index)
                }
            }
            visit(root, 0, -1)
            val code = when {
                classifier.classify(pkg) == AppTier.BLOCKED -> "blocked_app"
                protected -> "authentication_boundary"
                (pkg == AndroidGeminiDraftUi.GOOGLE_HOST && !gemini) ||
                    (!packageAllowed(pkg) && !(pkg == AndroidGeminiDraftUi.GOOGLE_HOST && gemini)) -> "blocked_or_cautious_app"
                nodes.isEmpty() -> "screen_unreadable"
                else -> reason(service)
            }
            block(ControlFrame(pkg, root.windowId, operationHash(identity.toString()), nodes, code, truncated), native)
        } finally {
            owned.forEach { it.recycleCompat() }
            windows.forEach { runCatching { it.recycle() } }
        }
    }
    override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
        val service = AgentService.instance ?: return ControlEffect(false, false, "accessibility_permission_missing")
        secureWindow(service)?.let { return ControlEffect(false, false, it) }
        val action = input.getValue("action").jsonPrimitive.content
        val effect = withTree(service) { fresh, native ->
            val failure = fresh.reason ?: if (fresh.truncated || fresh.packageName != frame.packageName ||
                fresh.windowId != frame.windowId || fresh.fingerprint != frame.fingerprint) "screen_changed" else null
            if (failure != null) return@withTree ControlEffect(false, false, failure)
            reason(service)?.let { return@withTree ControlEffect(false, false, it) }
            mutationBlock(fresh.packageName)?.let { return@withTree ControlEffect(false, false, it) }
            if (action == "home" || action == "back") {
                val result = platform(service).performAction(UIAction.SystemButton(if (action == "home") SystemButtonType.HOME else SystemButtonType.BACK))
                return@withTree ControlEffect(true, result is ActionResult.Success, "system_button_acknowledged")
            }
            if (action == "open_app") {
                val pkg = input["package_name"]?.jsonPrimitive?.content.orEmpty()
                if (pkg !in listedPackages || !packageAllowed(pkg)) return@withTree ControlEffect(false, false, "app_not_selected_or_blocked")
                val result = platform(service).launchApp(pkg)
                return@withTree ControlEffect(true, result is ActionResult.Success, "app_launch_acknowledged")
            }
            val at = fresh.nodes.indexOfFirst { it.id == node?.id }
            val target = native.getOrNull(at) ?: return@withTree ControlEffect(false, false, "target_unavailable")
            if (!target.refresh() || !target.isVisibleToUser || !target.isEnabled || target.isPassword || target.packageName?.toString() != fresh.packageName)
                return@withTree ControlEffect(false, false, "target_changed")
            val expected = fresh.nodes[at]
            val rect = Rect(); target.getBoundsInScreen(rect)
            val currentText = if (target.isShowingHintText) "" else target.text?.toString().orEmpty().take(2001)
            if (currentText != expected.text || target.contentDescription?.toString().orEmpty().take(100) != expected.description ||
                target.viewIdResourceName.orEmpty().take(100) != expected.resourceId || rect.flattenToString() != expected.bounds ||
                (Build.VERSION.SDK_INT >= 34 && target.isAccessibilityDataSensitive))
                return@withTree ControlEffect(false, false, "target_changed")
            // Atomic native node actions match the existing Gemini adapter; no focus/tap/retry fallback.
            val acknowledged = when (action) {
                "click" -> target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                "long_press" -> target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                "scroll" -> target.performAction(if (input.getValue("direction").jsonPrimitive.content == "down")
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                "type" -> {
                    if (!target.isEditable || (!target.isShowingHintText && !target.text.isNullOrEmpty()))
                        return@withTree ControlEffect(false, false, "draft_not_empty")
                    target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, input.getValue("text").jsonPrimitive.content)
                    })
                }
                else -> return@withTree ControlEffect(false, false, "unsupported_action")
            }
            ControlEffect(true, acknowledged, if (acknowledged) "action_acknowledged" else "action_unconfirmed")
        }
        if (effect.attempted) delay(350)
        return effect
    }
}
