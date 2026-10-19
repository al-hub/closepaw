package ai.closepaw.chatgpt.control

import ai.closepaw.app.AgentService
import ai.closepaw.chatgpt.AndroidGeminiDraftUi
import ai.closepaw.chatgpt.GeminiDraftComposerPolicy
import ai.closepaw.chatgpt.GeminiReadIdentity
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
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.Path
import android.graphics.Region
import android.graphics.RegionIterator
import android.hardware.display.DisplayManager
import android.view.accessibility.AccessibilityManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

/** Native adapter: semantic targets only; explicit guarded node tap, never automatic gesture fallback. */
internal class AndroidControlPort(context: Context, private val mutationBlock: (String) -> String? = { null },
    private val runtime: RuntimeControlSettings? = null, private val allowed: () -> Boolean) : ControlPort {
    private val context = context.applicationContext
    private val classifier = AppClassifierHolder.get(this.context)
    private var listedPackages = emptySet<String>()
    private var catalogDiagnostics = buildJsonObject {}
    override fun appDiagnostics() = catalogDiagnostics
    private var certifiedWindows: NativeControlWindows? = null
    private var windowDiagnostics: JsonObject? = null
    private val probeThrottle = ScreenProbeThrottle({ SystemClock.elapsedRealtime() })
    override fun invalidate() { listedPackages = emptySet(); probeThrottle.reset(); certifiedWindows = null; windowDiagnostics = null }
    private fun reason(service: AgentService?): String? = when {
        !allowed() -> "generic_control_disabled"
        service == null -> "accessibility_permission_missing"
        AgentService.instance !== service -> "accessibility_service_changed"
        context.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !context.getSystemService(PowerManager::class.java).isInteractive -> "device_locked"
        service.getActiveSession()?.state?.value in setOf(SessionState.Running, SessionState.TakeoverPending) -> "local_agent_busy"
        else -> null
    }
    private fun denied(code: String, diagnostics: JsonObject? = null) = ControlFrame("", -1, "", emptyList(), reason = code, windowDiagnostics = diagnostics)
    private fun platform(service: AgentService) = AccessibilityPlatform(service, SessionConfig(),
        isPackageBlocked = { classifier.classify(it) == AppTier.BLOCKED })

    override suspend fun apps(filter: String): JsonArray {
        // Invalidate old selections before any failure or cancellation.
        listedPackages = emptySet()
        val service = AgentService.instance
        val refusal = withContext(Dispatchers.Main) { reason(service) }
        if (refusal != null || service == null) throw AppCatalogUnavailable(refusal ?: "accessibility_permission_missing")
        val launcher = withContext(Dispatchers.IO) {
            context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) },
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }
        var queried = 0
        var protectedExcluded = 0
        // Discovery is not authorization. Return matching identities with separate capabilities.
        val matches = AndroidAppCatalog(context.packageManager, { pkg ->
            queried++
            val include = pkg != context.packageName && pkg != "com.android.systemui" &&
                !pkg.contains("permissioncontroller") && !pkg.contains("packageinstaller")
            if (!include) protectedExcluded++
            include
        }).list(filter)
        catalogDiagnostics = buildJsonObject {
            put("catalog_candidates_examined", queried); put("matched_returned", matches.size)
            put("protected_candidates_excluded", protectedExcluded)
            put("access_tier_filter_applied", false); put("os_visibility_diagnosis", "not_measured")
        }
        if (withContext(Dispatchers.Main) { reason(service) } != null) throw AppCatalogUnavailable("device_state_changed")
        listedPackages = matches.map { it.packageName }.toSet()
        return JsonArray(matches.map { buildJsonObject {
            put("label", it.label.take(128)); put("package_name", it.packageName)
            put("launch_allowed", packageAllowed(it.packageName, launcher, false, "launch"))
            put("read_allowed", packageAllowed(it.packageName, launcher, false, "read"))
            put("interaction_allowed", packageAllowed(it.packageName, launcher, false, "interact"))
            put("access_reason", if (classifier.classify(it.packageName) == AppTier.BLOCKED) "blocked_app" else
                if (!packageAllowed(it.packageName, launcher, false)) "runtime_access_required" else "available")
        } })
    }
    private fun packageAllowed(pkg: String, knownLauncher: String? = null, resolveLauncher: Boolean = true, operation: String = "read"): Boolean {
        if (pkg == context.packageName || pkg.contains("permissioncontroller") || pkg.contains("packageinstaller") ||
            pkg == "com.android.systemui") return false
        if (classifier.classify(pkg) == AppTier.BLOCKED) return false
        val home = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val launcher = if (resolveLauncher) context.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName else knownLauncher
        val normal = classifier.classify(pkg) == AppTier.NORMAL || pkg == launcher
        return runtime?.access(pkg, normal, operation) ?: normal
    }
    override suspend fun capture(): ControlFrame {
        val service = AgentService.instance
        reason(service)?.let { return denied(it) }
        service ?: return denied("accessibility_permission_missing")
        // Unlike a general accessibility tree, this signal can reject FLAG_SECURE windows.
        // On older Android the adapter declines to claim a secure-window check it cannot perform.
        secureWindow(service)?.let { return denied(it, windowDiagnostics) }
        return withTree(service) { frame, _ -> frame }
    }
    private suspend fun secureWindow(service: AgentService): String? {
        certifiedWindows = null
        windowDiagnostics = null
        if (Build.VERSION.SDK_INT < 34) return "secure_window_probe_requires_android14"
        val selected = withContext(Dispatchers.Main) {
            reason(service)?.let { return@withContext null }
            val root = service.rootInActiveWindow ?: return@withContext null
            val windows = service.windows.orEmpty()
            try { controlWindows(root.windowId, root.packageName?.toString().orEmpty(), windows) }
            finally { root.recycleCompat(); windows.forEach { runCatching { it.recycle() } } }
        } ?: return reason(service) ?: "active_root_missing"
        windowDiagnostics = selected.diagnostics
        selected.selection.reason?.let { return it }
        // Every included root gets a NEW security verdict; no previous capture's verdict is reused.
        for (id in selected.selection.members) {
            probeThrottle.awaitSlot()
            val failure = withContext(Dispatchers.Main) {
                reason(service)?.let { return@withContext it }
                suspendCancellableCoroutine<String?> { continuation ->
                    try {
                        service.takeScreenshotOfWindow(id, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                                screenshot.hardwareBuffer.close()
                                if (continuation.isActive) continuation.resume(null)
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
            if (failure != null) return failure
        }
        certifiedWindows = selected
        return null
    }
    private suspend fun <T> withTree(service: AgentService,
        block: suspend (ControlFrame, List<AccessibilityNodeInfo>) -> T): T = withContext(Dispatchers.Main) {
        reason(service)?.let { return@withContext block(denied(it), emptyList()) }
        val root = service.rootInActiveWindow ?: return@withContext block(denied("active_root_missing"), emptyList())
        val owned = mutableListOf(root)
        val windows = service.windows.orEmpty()
        try {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank()) return@withContext block(denied("package_unknown"), emptyList())
            val selected = controlWindows(root.windowId, pkg, windows)
            selected.selection.reason?.let { return@withContext block(denied(it, selected.diagnostics), emptyList()) }
            val certified = certifiedWindows
            if (certified == null || certified.signature != selected.signature || certified.selection != selected.selection)
                return@withContext block(denied("secure_window_changed", selected.diagnostics), emptyList())
            val window = windows.single { it.id == selected.selection.target }
            val configuration = runtime?.snapshot()
            val revision = configuration?.revision ?: 0
            val nodeBudget = configuration?.config?.getValue("node_budget")?.jsonPrimitive?.int ?: 512
            val outputLimit = configuration?.config?.getValue("output_limit")?.jsonPrimitive?.int ?: 50
            val scanStarted = SystemClock.elapsedRealtime()
            val nodes = mutableListOf<ControlNode>()
            val native = mutableListOf<AccessibilityNodeInfo>()
            val identity = StringBuilder("configuration=$revision\n").append(selected.signature).append('\n')
            var truncated = false
            var protected = false
            var gemini = false
            val shellIds = mutableSetOf<String>()
            var visited = 0
            var deepest = 0
            val truncationReasons = linkedSetOf<String>()
            fun visit(node: AccessibilityNodeInfo, depth: Int, parent: Int, expose: Boolean) {
                deepest = maxOf(deepest, depth)
                if (depth > 64 || visited >= nodeBudget || SystemClock.elapsedRealtime() - scanStarted > 2500) {
                    truncated = true
                    truncationReasons.add(if (depth > 64) "depth_limit" else if (visited >= nodeBudget) "node_limit" else "scan_time_limit")
                    return
                }
                val index = visited++
                val rect = Rect(); node.getBoundsInScreen(rect)
                val text = node.text?.toString().orEmpty()
                val description = node.contentDescription?.toString().orEmpty()
                val resource = node.viewIdResourceName.orEmpty()
                val hint = node.hintText?.toString().orEmpty()
                if (node.isVisibleToUser && node.packageName?.toString() == pkg) shellIds.add(resource)
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
                    put("selected", node.isSelected); put("checkable", node.isCheckable); put("checked", node.isChecked)
                    put("child_count", node.childCount); put("actions", node.actionList.joinToString { it.id.toString() })
                }
                identity.append(json.toString()).append('\n')
                if (expose && node.isVisibleToUser && !rect.isEmpty &&
                    (text.isNotEmpty() || description.isNotEmpty() || node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable ||
                        node.actionList.any { it.id in setOf(AccessibilityNodeInfo.ACTION_CLICK, AccessibilityNodeInfo.ACTION_EXPAND, AccessibilityNodeInfo.ACTION_COLLAPSE) })) {
                    run {
                        nodes.add(ControlNode(index, if (node.isShowingHintText) "" else text.take(2001), description.take(100), resource.take(100),
                            node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }, node.isEditable, node.isScrollable,
                            node.isEnabled, node.isPassword, node.isShowingHintText || text.isEmpty(), rect.flattenToString(), parent, node.isLongClickable,
                            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_EXPAND },
                            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_COLLAPSE },
                            node.className?.toString().orEmpty(), node.isSelected, node.isCheckable, node.isChecked))
                        native.add(node)
                    }
                }
                for (i in 0 until node.childCount) {
                    if (visited >= nodeBudget) { truncated = true; truncationReasons.add("node_limit"); break }
                    if (SystemClock.elapsedRealtime() - scanStarted > 2500) {
                        truncated = true; truncationReasons.add("scan_time_limit"); break
                    }
                    val child = node.getChild(i)
                    if (child == null) { truncated = true; truncationReasons.add("child_unavailable"); continue }
                    owned.add(child); visit(child, depth + 1, index, expose)
                }
            }
            var traversalFailure: String? = null
            // Binder-backed node reads and fingerprint construction can be large on launchers.
            // Keep traversal off Main; all native effects still execute on Main after revalidation.
            val fingerprint = withContext(Dispatchers.Default) {
                for (id in selected.selection.members) {
                    val member = if (id == root.windowId) root else windows.single { it.id == id }.root
                    if (member == null || member.windowId != id || member.packageName?.toString() != pkg) {
                        if (member != null && member !== root) member.recycleCompat()
                        traversalFailure = "owned_window_root_changed"; break
                    }
                    if (member !== root) owned.add(member)
                    identity.append("window=").append(id).append('\n')
                    visit(member, 0, -1, id == selected.selection.target)
                }
                operationHash(identity.toString())
            }
            traversalFailure?.let { return@withContext block(denied(it, selected.diagnostics), emptyList()) }
            reason(service)?.let { return@withContext block(denied(it), emptyList()) }
            if ((runtime?.snapshot()?.revision ?: 0) != revision)
                return@withContext block(denied("configuration_revision_changed"), emptyList())
            val currentRoot = service.rootInActiveWindow
                ?: return@withContext block(denied("active_root_missing"), emptyList())
            val currentWindows = service.windows.orEmpty()
            val stable = try {
                currentRoot.windowId == root.windowId && currentRoot.packageName?.toString() == pkg &&
                    controlWindows(currentRoot.windowId, pkg, currentWindows).let {
                        it.signature == selected.signature && it.selection == selected.selection
                    }
            } finally {
                currentRoot.recycleCompat(); currentWindows.forEach { runCatching { it.recycle() } }
            }
            if (!stable) return@withContext block(denied("screen_changed", selected.diagnostics), emptyList())
            val code = GenericScreenPolicy.refusal(
                blocked = classifier.classify(pkg) == AppTier.BLOCKED, sensitive = protected,
                packageAllowed = packageAllowed(pkg), isGeminiHost = pkg == AndroidGeminiDraftUi.GOOGLE_HOST,
                verifiedGemini = gemini || (!truncated && GeminiReadIdentity.nativeShell(pkg, shellIds)), readable = nodes.isNotEmpty(),
            ) ?: reason(service)
            block(ControlFrame(pkg, window.id, fingerprint, nodes, code, truncated,
                truncationReasons, visited, deepest, touchContext(service, window, windows), root.windowId,
                selected.signature, selected.diagnostics, revision, outputLimit), native)
        } finally {
            owned.forEach { it.recycleCompat() }
            windows.forEach { runCatching { it.recycle() } }
        }
    }
    private fun bounds(rect: Rect) = TapBounds(rect.left, rect.top, rect.right, rect.bottom)
    private fun windowBounds(window: AccessibilityWindowInfo): Rect = Rect().also { window.getBoundsInScreen(it) }

    /** Includes IME, system and accessibility overlays, even when not in the active node tree. */
    private fun touchContext(service: AgentService, window: AccessibilityWindowInfo, windows: List<AccessibilityWindowInfo>): String {
        // Android applies magnification/exploration to injected gestures, so screen coordinates
        // are not an identity-preserving target in those modes.
        if (Build.VERSION.SDK_INT < 34) return ""
        val magnification = service.magnificationController.magnificationConfig ?: return ""
        if (context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled ||
            magnification.isActivated) return ""
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
            ?: return ""
        if (window.displayId != android.view.Display.DEFAULT_DISPLAY || window.isInPictureInPictureMode || !bounds(windowBounds(window)).valid ||
            windows.isEmpty() || windows.size > 64 || windows.map { it.id }.distinct().size != windows.size) return ""
        val geometry = StringBuilder("rotation=${display.rotation}\n")
        for (item in windows.sortedBy { it.id }) {
            val region = Region(); item.getRegionInScreen(region)
            val parts = mutableListOf<String>(); val iterator = RegionIterator(region); val part = Rect()
            while (iterator.next(part)) {
                if (parts.size >= 256) return ""
                parts.add(part.flattenToString())
            }
            geometry.append("${item.id}:${item.displayId}:${item.type}:${item.layer}:${item.isActive}:${item.isFocused}:${item.isInPictureInPictureMode}:")
                .append(windowBounds(item).flattenToString()).append(":").append(parts.joinToString(";")).append('\n')
        }
        return operationHash(geometry.toString())
    }
    private suspend fun tap(service: AgentService, frame: ControlFrame, targetBounds: Rect): ControlEffect {
        // Reacquire windows immediately before dispatch, while still on Main. No suspension between
        // these checks and dispatchGesture; the gesture has no caller-controlled coordinates.
        val windows = service.windows.orEmpty()
        try {
            val root = service.rootInActiveWindow ?: return ControlEffect(false, false, "active_root_missing")
            val rootMatches = try { root.windowId == frame.foregroundWindowId && root.packageName?.toString() == frame.packageName }
                finally { root.recycleCompat() }
            if (!rootMatches) return ControlEffect(false, false, "screen_changed")
            val window = windows.singleOrNull { it.id == frame.windowId }
                ?: return ControlEffect(false, false, "touch_window_unavailable")
            val selected = controlWindows(frame.foregroundWindowId, frame.packageName, windows)
            if (selected.selection.reason != null || selected.selection.target != frame.windowId ||
                selected.signature != frame.windowContext || frame.touchContext.isEmpty() ||
                touchContext(service, window, windows) != frame.touchContext)
                return ControlEffect(false, false, "touch_window_changed")
            val covering = windows.filter { it.id != window.id && it.displayId == window.displayId && it.layer >= window.layer }
                .map { bounds(windowBounds(it)) }
            val point = NodeTapPolicy.point(bounds(targetBounds), bounds(windowBounds(window)), covering)
                ?: return ControlEffect(false, false, "touch_target_clipped_or_covered")
            val region = Region(); window.getRegionInScreen(region)
            if (!region.contains(point.first, point.second)) return ControlEffect(false, false, "touch_target_outside_region")
            reason(service)?.let { return ControlEffect(false, false, it) }
            mutationBlock(frame.packageName)?.let { return ControlEffect(false, false, it) }
            val path = Path().apply { moveTo(point.first.toFloat(), point.second.toFloat()) }
            val gesture = GestureDescription.Builder().setDisplayId(android.view.Display.DEFAULT_DISPLAY)
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
            return suspendCancellableCoroutine { continuation ->
                val submitted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(ControlEffect(true, true, "gesture_completed"))
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(ControlEffect(true, false, "gesture_cancelled"))
                    }
                }, null)
                // Even a negative dispatch acknowledgement is conservatively not replayable.
                if (!submitted && continuation.isActive) continuation.resume(ControlEffect(true, false, "gesture_unconfirmed"))
            }
        } finally { windows.forEach { runCatching { it.recycle() } } }
    }
    override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
        val service = AgentService.instance ?: return ControlEffect(false, false, "accessibility_permission_missing")
        secureWindow(service)?.let { return ControlEffect(false, false, it) }
        val action = input.getValue("action").jsonPrimitive.content
        val effect = withTree(service) { fresh, native ->
            val failure = GenericControlActionPolicy.refusal(frame, fresh, action)
            if (failure != null) return@withTree ControlEffect(false, false, failure)
            reason(service)?.let { return@withTree ControlEffect(false, false, it) }
            mutationBlock(fresh.packageName)?.let { return@withTree ControlEffect(false, false, it) }
            if (action == "home" || action == "back") {
                val result = platform(service).performAction(UIAction.SystemButton(if (action == "home") SystemButtonType.HOME else SystemButtonType.BACK))
                return@withTree ControlEffect(true, result is ActionResult.Success, "system_button_acknowledged")
            }
            if (action == "open_app") {
                val pkg = input["package_name"]?.jsonPrimitive?.content.orEmpty()
                if (pkg !in listedPackages || !packageAllowed(pkg, operation = "launch")) return@withTree ControlEffect(false, false, "app_not_selected_or_blocked")
                val result = if (Build.VERSION.SDK_INT >= 33) {
                    try {
                        context.packageManager.getLaunchIntentSenderForPackage(pkg).sendIntent(service, 0, null, null, null)
                        true
                    } catch (_: Exception) { false }
                } else platform(service).launchApp(pkg) is ActionResult.Success
                return@withTree ControlEffect(true, result, "app_launch_acknowledged")
            }
            if (!packageAllowed(fresh.packageName, operation = "interact"))
                return@withTree ControlEffect(false, false, "runtime_interaction_not_allowed")
            val at = fresh.nodes.indexOfFirst { it.id == node?.id }
            val target = native.getOrNull(at) ?: return@withTree ControlEffect(false, false, "target_unavailable")
            if (!target.refresh() || !target.isVisibleToUser || !target.isEnabled || target.isPassword || target.windowId != fresh.windowId || target.packageName?.toString() != fresh.packageName)
                return@withTree ControlEffect(false, false, "target_changed")
            val expected = fresh.nodes[at]
            val rect = Rect(); target.getBoundsInScreen(rect)
            val currentText = if (target.isShowingHintText) "" else target.text?.toString().orEmpty().take(2001)
            if (currentText != expected.text || target.contentDescription?.toString().orEmpty().take(100) != expected.description ||
                target.viewIdResourceName.orEmpty().take(100) != expected.resourceId || rect.flattenToString() != expected.bounds ||
                (Build.VERSION.SDK_INT >= 34 && target.isAccessibilityDataSensitive))
                return@withTree ControlEffect(false, false, "target_changed")
            if (action == "tap") {
                if (!target.isClickable && target.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                    return@withTree ControlEffect(false, false, "target_not_clickable")
                return@withTree tap(service, fresh, rect)
            }
            // Atomic native node actions match the existing Gemini adapter; no focus/tap/retry fallback.
            val acknowledged = when (action) {
                "click" -> target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                "expand", "collapse" -> {
                    val nativeAction = if (action == "expand") AccessibilityNodeInfo.ACTION_EXPAND else AccessibilityNodeInfo.ACTION_COLLAPSE
                    if (target.actionList.none { it.id == nativeAction }) return@withTree ControlEffect(false, false, "native_action_unavailable")
                    target.performAction(nativeAction)
                }
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
        return effect
    }
}
