package ai.closepaw.chatgpt.control

import ai.closepaw.app.AgentService
import ai.closepaw.chatgpt.AndroidGeminiDraftUi
import ai.closepaw.chatgpt.GeminiDraftComposerPolicy
import ai.closepaw.chatgpt.GeminiReadIdentity
import ai.closepaw.chatgpt.operations.operationHash
import ai.closepaw.platform.OwnedClipboardLease
import android.content.ClipboardManager
import kotlinx.coroutines.ensureActive
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
import android.content.ComponentName
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

/** Native adapter: semantic targets only; explicit guarded node tap, never automatic gesture fallback. */
internal class AndroidControlPort(context: Context, private val mutationBlock: (String) -> String? = { null },
    private val runtime: RuntimeControlSettings? = null, private val localAuthority: LocalInputAuthority? = null, private val allowed: () -> Boolean) : ControlPort {
    override val validatesAtNativeBoundary = true
    override fun islandRefusal(sessionId: String): String? {
        val service = AgentService.instance ?: return "accessibility_permission_missing"
        val session = sessionFor(service)
        if (!session.explicit || session.state != ControlSession.State.ACTIVE) return "explicit_active_session_required"
        session.refusal(sessionId, mutation = true)?.let { return it }
        return reason(service)
    }
    override suspend fun observeIsland(): IslandBounds? = withContext(Dispatchers.Main) {
        val service = AgentService.instance ?: return@withContext null
        if (islandRefusal(sessionFor(service).id) != null) return@withContext null
        service.observedIsland()
    }
    override suspend fun dragIsland(before: IslandBounds, dx: Int, dy: Int, holdMs: Long, moveMs: Long, sessionId: String): Boolean =
        withContext(Dispatchers.Main) {
            val service = AgentService.instance ?: return@withContext false
            val epoch = sessionFor(service).epoch
            if (islandRefusal(sessionId) != null) return@withContext false
            service.dragIsland(before, dx, dy, holdMs, moveMs) {
                AgentService.instance === service && sessionFor(service).epoch == epoch && islandRefusal(sessionId) == null
            }
        }
    private fun sessionFor(service: AgentService): ControlSession = localAuthority?.session ?: service.remoteControlSession
    override fun session() = AgentService.instance?.let(::sessionFor)
    override fun bindResume(check: suspend () -> Boolean) { AgentService.instance?.remoteResumeCheck = check }
    override suspend fun feedback(label: String) { withContext(Dispatchers.Main) { session()?.feedback(label) } }
    override suspend fun feedbackAtEpoch(label: String, expectedEpoch: Long?) {
        withContext(Dispatchers.Main) { session()?.feedback(label, expectedEpoch) }
    }
    override suspend fun presentation(phase: ControlSession.VisualPhase, expectedEpoch: Long?) {
        withContext(Dispatchers.Main) { session()?.present(phase, expectedEpoch) }
    }
    private var activityService: AgentService? = null
    private var actionChangeVersion = 0L
    override suspend fun activity(active: Boolean) {
        withContext(Dispatchers.Main) {
            if (active) {
                val service = AgentService.instance
                if (reason(service) == null && service != null) {
                    activityService = service
                    service.showRemoteControlActivity()
                }
            } else {
                try { activityService?.hideRemoteControlActivity() } finally { activityService = null }
            }
        }
    }
    override suspend fun feedbackTest(durationMs: Long): ai.closepaw.chatgpt.operations.OperationResult {
        val service = AgentService.instance
        val initial = withContext(Dispatchers.Main) { reason(service) }
        if (initial != null || service == null) return ai.closepaw.chatgpt.operations.OperationResult(
            success = false, summary = initial ?: "accessibility_permission_missing")
        // Use the same guarded screen capture before showing anything; protected surfaces remain refused.
        val initialFrame = capture()
        if (initialFrame.reason != null) return ai.closepaw.chatgpt.operations.OperationResult(success = false,
            summary = initialFrame.reason, data = buildJsonObject { put("reason_code", initialFrame.reason) })
        return try {
            ControlFeedbackTest({ SystemClock.elapsedRealtime() },
                { withContext(Dispatchers.Main) { reason(service) } },
                { active -> withContext(Dispatchers.Main) {
                    if (active) service.showRemoteControlFeedbackTest() else service.hideRemoteControlActivity(force = true)
                } },
                { withContext(Dispatchers.Main) { service.isRemoteControlActivityAttached() } }).run(durationMs)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { service.showRemoteControlActivity() }
        }
    }

    override suspend fun captureAfter(frame: ControlFrame, input: JsonObject): ControlFrame {
        val service = AgentService.instance ?: return capture()
        return ControlPostObserver({ SystemClock.elapsedRealtime() }, service.remoteControlChanges::version,
            service.remoteControlChanges::awaitAfter, ::capture).observe(frame, input, actionChangeVersion).also {
                val timing = currentCoroutineContext()[ControlTiming]
                timing?.mark(ControlTiming.Event.GUARDED_SNAPSHOT_AVAILABLE, id = timing.nextId(),
                    step = currentCoroutineContext()[ControlTrace]?.step ?: 0)
            }
    }
    private fun controlledWindows(service: AgentService, foreground: Int, pkg: String,
        windows: List<AccessibilityWindowInfo>): NativeControlWindows {
        val selected = controlWindows(foreground, pkg, windows.filterNot {
            it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && service.ownsRemoteControlIndicatorWindow(it.id)
        })
        if (selected.selection.reason != null) return selected
        fun refuse() = selected.copy(selection = selected.selection.copy(reason = "input_method_unverified"))
        val keyboards = windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (keyboards.isEmpty()) return selected
        if (keyboards.size != 1) return refuse()
        val window = keyboards.single()
        if (window.displayId != android.view.Display.DEFAULT_DISPLAY || window.isInPictureInPictureMode) return refuse()
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return refuse()
        val component = ComponentName.unflattenFromString(id) ?: return refuse()
        val inputMethod = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList
            .singleOrNull { it.id == id && it.packageName == component.packageName } ?: return refuse()
        val root = window.root ?: return refuse()
        val owned = try { root.windowId == window.id && root.packageName?.toString() == inputMethod.packageName }
            finally { root.recycleCompat() }
        if (!owned) return refuse()
        val keyboard = ControlKeyboard(window.id, inputMethod.packageName, id,
            readAllowed = packageAllowed(inputMethod.packageName, operation = "read"),
            interactAllowed = packageAllowed(inputMethod.packageName, operation = "interact"))
        return selected.copy(signature = operationHash(selected.signature + "\n" + keyboard.toString()), keyboard = keyboard)
    }
    private val context = context.applicationContext
    private val classifier = AppClassifierHolder.get(this.context)
    private val appCatalog = AndroidAppCatalog(this.context.packageManager) { pkg ->
        pkg != this.context.packageName && pkg != "com.android.systemui" &&
            !pkg.contains("permissioncontroller") && !pkg.contains("packageinstaller")
    }
    private val appSelections = ControlAppSelections()
    private var catalogDiagnostics = buildJsonObject {}
    override fun appDiagnostics() = catalogDiagnostics
    private var certifiedWindows: NativeControlWindows? = null
    private var windowDiagnostics: JsonObject? = null
    private val probeThrottle = ScreenProbeThrottle({ SystemClock.elapsedRealtime() })
    override fun clearSelections() { appSelections.clear() }
    private fun editorGeneration(service: AgentService?): Long? = if(Build.VERSION.SDK_INT>=33)
        (service?.inputMethod as? ai.closepaw.platform.GuardedAccessibilityInputMethod)?.generation else null
    private fun editorGenerationMatches(proof: VisualTargetProof?,service: AgentService?): Boolean =
        proof?.let {ai.closepaw.platform.InputTypePrivacyPolicy.sameGeneration(it.editorPrivacyGeneration,editorGeneration(service))}==true
    private fun editorImageRefusal(service: AgentService?, pkg: String): String? =
        if(Build.VERSION.SDK_INT>=33 && (service?.inputMethod as? ai.closepaw.platform.GuardedAccessibilityInputMethod)?.imagePrivacy?.let {
            it.packageName==pkg && it.sensitive
        }==true) "visual_sensitive_editor" else null
    override fun visualDisclosureAllowed(frame: ControlFrame): Boolean = frame.visual?.sourceDisclosureAllowed() == true && reason(AgentService.instance) == null &&
        editorImageRefusal(AgentService.instance,frame.packageName)==null && editorGenerationMatches(frame.visual,AgentService.instance) &&
        packageAllowed(frame.packageName, operation = "read") && (runtime?.snapshot()?.revision ?: 0L) == frame.configurationRevision &&
        (frame.visual?.scope !in setOf("keyboard", "display") || (frame.visual?.scope == "display" && frame.keyboard == null) || frame.keyboard?.let {
            packageAllowed(it.packageName, operation = "read") &&
                Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == it.inputMethodId
        } == true) && (frame.visual?.scope != "display" || AgentService.instance?.let { service ->
            val proof=frame.visual ?: return@let false
            // Cache reads can run off Main; never read mutable decoration Views
            // here. Native window-event generation invalidates the whole proof.
            proof.displayGeneration == service.remoteDisplayChanges.version()
        } == true)
    override fun invalidate() { probeThrottle.reset(); certifiedWindows = null; windowDiagnostics = null }
    private fun reason(service: AgentService?): String? = when {
        localAuthority?.refusal() != null -> localAuthority?.refusal()
        !allowed() -> "generic_control_disabled"
        session()?.refusal(session()?.id) != null -> session()?.refusal(session()?.id)
        service == null -> "accessibility_permission_missing"
        AgentService.instance !== service -> "accessibility_service_changed"
        context.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !context.getSystemService(PowerManager::class.java).isInteractive -> "device_locked"
        localAuthority == null && service.getActiveSession()?.state?.value in setOf(SessionState.Running, SessionState.TakeoverPending) -> "local_agent_busy"
        else -> null
    }
    private fun denied(code: String, diagnostics: JsonObject? = null) = ControlFrame("", -1, "", emptyList(), reason = code, windowDiagnostics = diagnostics)
    private fun platform(service: AgentService) = AccessibilityPlatform(service, SessionConfig(),
        isPackageBlocked = { classifier.classify(it) == AppTier.BLOCKED })

    override suspend fun apps(filter: String): JsonArray {
        // Searches do not revoke prior exact selections; configuration changes do.
        val service = AgentService.instance
        val refusal = withContext(Dispatchers.Main) { reason(service) }
        if (refusal != null || service == null) throw AppCatalogUnavailable(refusal ?: "accessibility_permission_missing")
        val launcher = withContext(Dispatchers.IO) {
            context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) },
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }
        // Discovery is not authorization. Return matching identities with separate capabilities.
        val matches = appCatalog.list(filter)
        catalogDiagnostics = buildJsonObject {
            put("matched_returned", matches.size)
            put("access_tier_filter_applied", false); put("os_visibility_diagnosis", "not_measured")
        }
        if (withContext(Dispatchers.Main) { reason(service) } != null) throw AppCatalogUnavailable("device_state_changed")
        appSelections.add(matches.map { it.packageName })
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
        // This default is deliberately read-only, selected by current OS evidence,
        // and never substitutes an app category/package-name allowlist for trust.
        val readDefault = !normal && operation == "read" && commonReadDefault(pkg)
        return runtime?.access(pkg, normal || readDefault, operation) ?: (normal || readDefault)
    }
    private fun commonReadDefault(pkg: String): Boolean {
        val session = session()
        val active = session != null && session.explicit && session.state == ControlSession.State.ACTIVE
        val bundled = classifier.bundledTier(pkg)
        val userTier = classifier.userOverrides.value[pkg]
        if (!active || bundled != null || userTier != null) return false
        return runCatching {
            val selected = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            val component = selected?.let(ComponentName::unflattenFromString)
            val ime = component?.packageName == pkg && context.getSystemService(InputMethodManager::class.java)
                .enabledInputMethodList.any { it.id == selected && it.packageName == pkg }
            if (ime) return@runCatching VerifiedReadDefaults.permits("read", active, bundled, userTier, true, false, false)
            // The resolved component alone cannot distinguish a browser from a
            // host/path-scoped default link handler. Require the matched filter.
            val browser = BrowserReadResolver.permits(pkg) { intent, flags ->
                context.packageManager.resolveActivity(intent, flags)
            }
            VerifiedReadDefaults.permits("read", active, bundled, userTier, ime, browser, browser)
        }.getOrDefault(false)
    }
    override suspend fun capture(): ControlFrame {
        val service = AgentService.instance
        reason(service)?.let { return denied(it) }
        service ?: return denied("accessibility_permission_missing")
        // Unlike a general accessibility tree, this signal can reject FLAG_SECURE windows.
        // On older Android the adapter declines to claim a secure-window check it cannot perform.
        feedback("화면 보안 확인 중")
        val started = SystemClock.elapsedRealtime()
        secureWindow(service)?.let {
            ControlDiagnostics.record("capture_probe", it, SystemClock.elapsedRealtime() - started)
            return denied(it, windowDiagnostics)
        }
        feedback("접근성 정보 읽는 중")
        val probedAt = SystemClock.elapsedRealtime()
        return withTree(service) { frame, _ -> frame }.also { frame ->
            ControlDiagnostics.record("capture", frame.reason ?: "completed", SystemClock.elapsedRealtime() - started,
                metrics = buildJsonObject {
                    put("probe_ms", probedAt - started); put("tree_ms", SystemClock.elapsedRealtime() - probedAt)
                    put("visited_nodes", frame.visitedNodes); put("truncated", frame.truncated)
                })
        }
    }
    private var visualFailureReason = "visual_observation_unavailable"
    override fun visualFailure() = visualFailureReason
    override suspend fun visual(frame: ControlFrame): VisualTargetProof? = captureVisual(frame, "app")
    override suspend fun visualKeyboard(frame: ControlFrame): VisualTargetProof? = captureVisual(frame, "keyboard")
    override suspend fun visualDisplay(frame: ControlFrame): VisualTargetProof? = captureVisual(frame, "display")
    private data class DisplayPlan(val bounds: TapBounds, val rotation: Int, val region: Region,
        val appBounds: String, val imeBounds: String, val surfaces: List<IsolatedVisualSurface>)
    private fun displayPlan(service: AgentService, frame: ControlFrame): DisplayPlan? {
        if (Build.VERSION.SDK_INT < 34) return null
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return null
        val size = android.graphics.Point(); display.getRealSize(size)
        val windows = service.windows.orEmpty()
        try {
            val selected = controlledWindows(service,frame.foregroundWindowId,frame.packageName,windows)
            if (selected.selection.reason != null || selected.signature != frame.windowContext) return null
            val owned = (selected.selection.members + listOfNotNull(frame.keyboard?.windowId)).toSet()
            val content = windows.filter { it.type != AccessibilityWindowInfo.TYPE_SYSTEM &&
                !(it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && service.ownsRemoteControlIndicatorWindow(it.id)) }
            if (windows.any { it.displayId != android.view.Display.DEFAULT_DISPLAY || it.isInPictureInPictureMode } ||
                DisplayCapturePolicy.refusal(size.x>0 && size.y>0 && frame.touchContext.isNotEmpty(),display.displayId,display.rotation,content.map { it.id },owned) != null) return null
            val app = windows.singleOrNull { it.id == frame.windowId } ?: return null
            if (touchContext(service,app,windows) != frame.touchContext) return null
            // Keep only OS-reported visible owned regions; system UI and our control
            // overlays are also subtracted, so notifications/controls never leak.
            val visible = Region()
            for (w in windows.filter { it.id in owned }) {
                val region=Region(); w.getRegionInScreen(region); visible.op(region,Region.Op.UNION)
            }
            for (w in windows.filter { it.id !in owned }) {
                val decoration=service.remoteDecorationScreenshotMask(w.id)
                if (decoration!=null) visible.op(decoration,Region.Op.DIFFERENCE)
                else visible.op(windowBounds(w),Region.Op.DIFFERENCE)
            }
            // Our decoration is known from its actual renderer even when the
            // interactive-window list omits its passive full-screen surface.
            service.remoteKnownDecorationScreenshotMask()?.let { visible.op(it,Region.Op.DIFFERENCE) }
            visible.op(Rect(0,0,size.x,size.y),Region.Op.INTERSECT)
            if (visible.isEmpty) return null
            val surfaces=mutableListOf<IsolatedVisualSurface>()
            val remaining=Region(visible)
            // Highest owned window supplies overlapping pixels. The complete
            // returned region must independently match isolated window sources.
            for(w in windows.filter {it.id in owned}.sortedByDescending {it.layer}) {
                val region=Region();w.getRegionInScreen(region);region.op(remaining,Region.Op.INTERSECT)
                if(!region.isEmpty) {
                    val parts=mutableListOf<TapBounds>();val iterator=RegionIterator(region);val rect=Rect()
                    while(iterator.next(rect)) { if(parts.size>=256) return null; parts.add(bounds(rect)) }
                    surfaces.add(IsolatedVisualSurface(w.id,bounds(windowBounds(w)),parts))
                    remaining.op(region,Region.Op.DIFFERENCE)
                }
            }
            if(!remaining.isEmpty || surfaces.isEmpty()) return null
            return DisplayPlan(TapBounds(0,0,size.x,size.y),display.rotation,visible,windowBounds(app).flattenToString(),
                windows.singleOrNull { it.id == frame.keyboard?.windowId }?.let { windowBounds(it).flattenToString() }.orEmpty(),surfaces)
        } finally { windows.forEach { runCatching { it.recycle() } } }
    }
    private suspend fun captureVisual(frame: ControlFrame, scope: String): VisualTargetProof? {
        fun fail(code: String): VisualTargetProof? { visualFailureReason=code; return null }
        visualFailureReason="visual_observation_unavailable"
        val imageConsentEpoch = ai.closepaw.security.ImageRevocationAuthority.currentEpoch
        if (!ai.closepaw.security.ImageRevocationAuthority.isConsentStillValid(imageConsentEpoch)) return fail("screen_share_revoked")
        if (frame.reason != null || frame.truncated || frame.navigationOnly || frame.touchContext.isEmpty()) return fail("visual_frame_unverified")
        val service = AgentService.instance ?: return fail("accessibility_permission_missing")
        val admittedSession = sessionFor(service)
        val admittedSessionId = admittedSession.id
        val admittedSessionEpoch = admittedSession.epoch
        val sessionOwner = java.lang.ref.WeakReference(admittedSession)
        islandRefusal(admittedSessionId)?.let { return fail(it) }
        val admittedEditorGeneration=editorGeneration(service) ?: return fail("visual_editor_privacy_unavailable")
        editorImageRefusal(service,frame.packageName)?.let {return fail(it)}
        val keyboardImage = scope != "app"
        val keyboard = frame.keyboard
        if ((scope == "keyboard" || (scope == "display" && keyboard != null)) &&
            VisualKeyboardPolicy.imageReadRefusal(keyboard, keyboard?.let { packageAllowed(it.packageName, operation = "read") } == true,
                scope == "keyboard", frame.inputTarget != null) != null) return fail("visual_keyboard_policy_denied")
        val captureId = if (scope == "keyboard") keyboard!!.windowId else frame.windowId
        if(scope=="display") {
            // These are our pixel-only intent/ripple cues, never user controls.
            // Remove them before planning both observations and key revalidation;
            // the border, X, island and all source/coverage checks stay in place.
            withContext(Dispatchers.Main) { service.clearRemoteDecorationScreenshotEffects() }
            kotlinx.coroutines.delay(50)
            islandRefusal(sessionFor(service).id)?.let {return fail(it)}
        }
        val displayGeneration = service.remoteDisplayChanges.version()
        val plan = if (scope=="display") withContext(Dispatchers.Main) { displayPlan(service,frame) } else null
        if (scope=="display" && plan==null) return fail("display_surface_unverified")
        val bounds = plan?.bounds ?: withContext(Dispatchers.Main) {
            val windows = service.windows.orEmpty()
            try { windows.singleOrNull { it.id == captureId }?.let { bounds(windowBounds(it)) } }
            finally { windows.forEach { runCatching { it.recycle() } } }
        } ?: return fail("visual_window_unavailable")
        val projection = if (scope == "display") ai.closepaw.platform.virtualdisplay.ProjectionImageTransport.active() else null
        if (scope == "display" && projection == null) return fail("screen_share_not_active")
        probeThrottle.awaitSlot()
        var captured = WindowVisualCapture.capture(service,captureId,bounds,frame.touchContext,scope=="display",plan?.region,plan?.surfaces.orEmpty(), projection = projection) { probeThrottle.awaitSlot() }
        // Retry only an explicit OS screenshot rate refusal, before any action.
        // Never retry a denied/masked/changed screen or replay a gesture.
        if (captured.proof == null && captured.reason == "screenshot_throttled") {
            probeThrottle.awaitSlot()
            captured = WindowVisualCapture.capture(service,captureId,bounds,frame.touchContext,scope=="display",plan?.region,plan?.surfaces.orEmpty(), projection = projection) { probeThrottle.awaitSlot() }
        }
        val proof = captured.proof ?: return fail(captured.reason ?: "visual_observation_unavailable")
        val after = capture()
        if(!ai.closepaw.platform.InputTypePrivacyPolicy.sameGeneration(admittedEditorGeneration,editorGeneration(service))) return fail("visual_editor_privacy_changed")
        editorImageRefusal(service,frame.packageName)?.let {return fail(it)}
        if (scope=="display") {
            // Accessibility window events can be coalesced by the service's25ms
            // notification timeout. Drain that bounded delivery interval before
            // comparing the monotonic generation; never replay a capture here.
            kotlinx.coroutines.delay(75)
            DisplayCapturePolicy.interveningChange(displayGeneration,service.remoteDisplayChanges.version())?.let { return fail(it) }
        }
        val refusal=GenericControlActionPolicy.refusal(frame,after,if(keyboardImage) "visual_key" else "visual_tap")
        if (refusal!=null) return fail("visual_post_" + refusal)
        if (keyboardImage && (frame.keyboard != after.keyboard || frame.inputTarget != after.inputTarget)) return fail("visual_post_keyboard_changed")
        if (plan!=null) {
            val latest=withContext(Dispatchers.Main) { displayPlan(service,after) } ?: return fail("display_post_surface_changed")
            if (latest.bounds!=plan.bounds || latest.rotation!=plan.rotation || latest.region!=plan.region || latest.surfaces!=plan.surfaces) return fail("display_post_geometry_changed")
        }
        val sourceCheck = proof.sourceDisclosureAllowed
        val disclosureCheck: () -> Boolean = {
            ai.closepaw.security.ImageRevocationAuthority.isConsentStillValid(imageConsentEpoch) &&
            sourceCheck() && sessionOwner.get()?.let {
                it.id == admittedSessionId && it.epoch == admittedSessionEpoch &&
                    it.explicit && it.state == ControlSession.State.ACTIVE
            } == true
        }
        if (!disclosureCheck()) return fail("visual_source_revoked_or_expired")
        return proof.copy(sourceDisclosureAllowed=disclosureCheck,scope=scope,sourceWindowId=if(scope=="display") -1 else captureId,
            sourceDisplayId=if(plan!=null) 0 else -1,rotation=plan?.rotation ?: -1,
            applicationBounds=plan?.appBounds.orEmpty(),imeBounds=plan?.imeBounds.orEmpty(),
            sourceImeWindowId=if(plan!=null) keyboard?.windowId ?: -1 else -1,displayGeneration=if(plan!=null) displayGeneration else -1,editorPrivacyGeneration=admittedEditorGeneration)
    }

    private suspend fun secureWindow(service: AgentService): String? = controlSpan("security_probe") { secureWindowChecked(service) }
    private suspend fun secureWindowChecked(service: AgentService): String? {
        certifiedWindows = null
        windowDiagnostics = null
        if (Build.VERSION.SDK_INT < 34) return "secure_window_probe_requires_android14"
        val selected = withContext(Dispatchers.Main) {
            reason(service)?.let { return@withContext null }
            val root = service.rootInActiveWindow ?: return@withContext null
            val windows = service.windows.orEmpty()
            try { controlledWindows(service, root.windowId, root.packageName?.toString().orEmpty(), windows) }
            finally { root.recycleCompat(); windows.forEach { runCatching { it.recycle() } } }
        } ?: return reason(service) ?: "active_root_missing"
        windowDiagnostics = selected.diagnostics
        selected.selection.reason?.let { return it }
        // Every included root gets a NEW security verdict; no previous capture's verdict is reused.
        for (id in (selected.selection.members + listOfNotNull(selected.keyboard?.takeIf { it.readAllowed }?.windowId))) {
            var failure = probeWindow(service, id)
            // A read-only API rate-limit refusal can follow another adapter's screenshot.
            // Retry only that explicit refusal, once, with API spacing and a NEW verdict.
            if (failure == "secure_window_probe_throttled") failure = probeWindow(service, id)
            if (failure != null) return failure
        }
        certifiedWindows = selected
        return null
    }
    private suspend fun probeWindow(service: AgentService, id: Int): String? {
        if (Build.VERSION.SDK_INT < 34) return "secure_window_probe_requires_android14"
        val timing = currentCoroutineContext()[ControlTiming]
        val probe = timing?.nextId() ?: 0
        val step = currentCoroutineContext()[ControlTrace]?.step ?: 0
        fun mark(event: ControlTiming.Event) { timing?.mark(event, probe, step) }
        mark(ControlTiming.Event.PROBE_QUEUED)
        controlSpan("probe_api_spacing") { probeThrottle.awaitSlot() }
        mark(ControlTiming.Event.PROBE_SLOT_READY)
        return withContext(Dispatchers.Main) {
            mark(ControlTiming.Event.MAIN_ENTERED)
            reason(service)?.let { return@withContext it }
            suspendCancellableCoroutine<String?> { continuation ->
                continuation.invokeOnCancellation { mark(ControlTiming.Event.PROBE_CANCELLED) }
                try {
                    sessionFor(service).feedback("보안 확인용 화면 캡처 중")
                    mark(ControlTiming.Event.PROBE_DISPATCH)
                    service.takeScreenshotOfWindow(id, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            mark(ControlTiming.Event.PROBE_CALLBACK_OK)
                            screenshot.hardwareBuffer.close()
                            if (continuation.isActive) continuation.resume(null)
                        }
                        override fun onFailure(errorCode: Int) {
                            mark(ControlTiming.Event.PROBE_CALLBACK_FAILED)
                            if (continuation.isActive) continuation.resume(when (errorCode) {
                                AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "secure_window"
                                AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "secure_window_changed"
                                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "secure_window_probe_throttled"
                                else -> "secure_window_probe_failed"
                            })
                        }
                    })
                } catch (_: Exception) {
                    mark(ControlTiming.Event.PROBE_EXCEPTION)
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
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank()) return@withContext block(denied("package_unknown"), emptyList())
            val selected = controlledWindows(service, root.windowId, pkg, windows)
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
            val terminalBinding = try { TerminalInputBinding.capture(service) } catch (_: Exception) { null }
            val identity = StringBuilder("configuration=$revision\n").append(selected.signature).append('\n')
            terminalBinding?.let { identity.append("input_generation=${it.generation}:${it.fieldId}\n") }
            var truncated = false
            var protected = false
            var gemini = false
            val shellIds = mutableSetOf<String>()
            val keyboardScan = KeyboardScanDiagnostics()
            var visited = 0
            var deepest = 0
            val truncationReasons = linkedSetOf<String>()
            fun visit(node: AccessibilityNodeInfo, depth: Int, parent: Int, expose: Boolean, keyboard: Boolean = false) {
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
                val key = if (keyboard) KeyboardKeyPolicy.identify(text, description, node.className?.toString().orEmpty(),
                    node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }, node.isTextEntryKey) else null
                val labelEvidence = if (keyboard) KeyboardKeyPolicy.identifyLabel(text, description) else null
                val visualKey = if (keyboard && node.childCount <= 1) VisualKeyboardPolicy.identify(text, description) else null
                if (keyboard) keyboardScan.record(text, description, node.className?.toString().orEmpty(),
                    node.isVisibleToUser, node.isEnabled, node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK },
                    node.isTextEntryKey, key != null)
                if (node.isVisibleToUser && node.packageName?.toString() == pkg) shellIds.add(resource)
                val sensitiveId = resource.substringAfterLast('/').lowercase()
                if (node.isPassword || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) ||
                    listOf("password", "credential", "biometric", "lock_settings", "pin_entry", "otp", "permission_allow").any { sensitiveId.contains(it) }) protected = true
                if (!keyboard && GeminiDraftComposerPolicy.isComposer(hint, description, resource.substringAfterLast('/'), true) && node.isEditable) gemini = true
                val json = buildJsonObject {
                    put("id", index); put("parent", parent); put("pkg", node.packageName?.toString().orEmpty())
                    put("text_hash", operationHash(text)); put("description_hash", operationHash(description)); put("hint", operationHash(hint))
                    put("resource", resource); put("class", node.className?.toString().orEmpty()); put("bounds", rect.flattenToString())
                    put("text_entry_key", node.isTextEntryKey); put("visible", node.isVisibleToUser); put("enabled", node.isEnabled); put("clickable", node.isClickable)
                    put("long_clickable", node.isLongClickable); put("editable", node.isEditable); put("scrollable", node.isScrollable); put("password", node.isPassword)
                    put("focused", node.isFocused); put("selection_start", node.textSelectionStart); put("selection_end", node.textSelectionEnd)
                    put("selected", node.isSelected); put("checkable", node.isCheckable); put("checked", node.isChecked)
                    put("child_count", node.childCount); put("actions", node.actionList.joinToString { it.id.toString() })
                }
                identity.append(json.toString()).append('\n')
                if (expose && (!keyboard || key != null || labelEvidence != null) && node.isVisibleToUser && !rect.isEmpty &&
                    (text.isNotEmpty() || description.isNotEmpty() || node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable || node.isFocusable ||
                        node.actionList.any { it.id in setOf(AccessibilityNodeInfo.ACTION_CLICK, AccessibilityNodeInfo.ACTION_EXPAND, AccessibilityNodeInfo.ACTION_COLLAPSE) })) {
                    run {
                        nodes.add(ControlNode(index, if (node.isShowingHintText) "" else text.take(2001), description.take(100), resource.take(100),
                            node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }, node.isEditable, node.isScrollable,
                            node.isEnabled, node.isPassword, node.isShowingHintText || text.isEmpty(), rect.flattenToString(), parent, node.isLongClickable,
                            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_EXPAND },
                            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_COLLAPSE },
                            node.className?.toString().orEmpty(), node.isSelected, node.isCheckable, node.isChecked,
                            focused = node.isFocused, selectionStart = node.textSelectionStart, selectionEnd = node.textSelectionEnd,
                            setText = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT },
                            paste = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE }, focusable = node.isFocusable,
                            scrollDirections = node.actionList.mapNotNull { action -> when (action.id) {
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> "up"
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> "down"
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id -> "left"
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id -> "right"
                                else -> null
                            } }.toSet(), sourceWindowId = node.windowId, sourcePackage = node.packageName?.toString().orEmpty(),
                            keyboardKey = (key ?: labelEvidence)?.label.orEmpty(), textEntryKey = node.isTextEntryKey,
                            visualKeyCandidate = key == null && visualKey != null,
                            terminal = terminalBinding?.evidence(node)))
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
                    owned.add(child); visit(child, depth + 1, index, expose, keyboard)
                }
            }
            var traversalFailure: String? = null
            // Binder-backed node reads and fingerprint construction can be large on launchers.
            // Keep traversal off Main; all native effects still execute on Main after revalidation.
            val fingerprint = controlSpan("fingerprint_traversal") { withContext(Dispatchers.Default) {
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
                selected.keyboard?.takeIf { it.readAllowed }?.let { keyboard ->
                    val member = windows.single { it.id == keyboard.windowId }.root
                    if (member == null || member.windowId != keyboard.windowId || member.packageName?.toString() != keyboard.packageName) {
                        member?.recycleCompat(); traversalFailure = "input_method_unverified"
                    } else {
                        owned.add(member); identity.append("keyboard=").append(keyboard.inputMethodId).append('\n')
                        visit(member, 0, -1, true, true)
                    }
                }
                operationHash(identity.toString())
            } }
            traversalFailure?.let { return@withContext block(denied(it, selected.diagnostics), emptyList()) }
            reason(service)?.let { return@withContext block(denied(it), emptyList()) }
            if ((runtime?.snapshot()?.revision ?: 0) != revision)
                return@withContext block(denied("configuration_revision_changed"), emptyList())
            val currentRoot = service.rootInActiveWindow
                ?: return@withContext block(denied("active_root_missing"), emptyList())
            val currentWindows = service.windows.orEmpty()
            val stable = try {
                currentRoot.windowId == root.windowId && currentRoot.packageName?.toString() == pkg &&
                    controlledWindows(service, currentRoot.windowId, pkg, currentWindows).let {
                        it.signature == selected.signature && it.selection == selected.selection
                    }
            } finally {
                currentRoot.recycleCompat(); currentWindows.forEach { runCatching { it.recycle() } }
            }
            if (!stable) return@withContext block(denied("screen_changed", selected.diagnostics), emptyList())
            val geminiIdentityVerified = gemini || (!truncated && GeminiReadIdentity.nativeShell(pkg, shellIds))
            val code = GenericScreenPolicy.refusal(
                blocked = classifier.classify(pkg) == AppTier.BLOCKED, sensitive = protected,
                packageAllowed = packageAllowed(pkg), isGeminiHost = pkg == AndroidGeminiDraftUi.GOOGLE_HOST,
                verifiedGemini = geminiIdentityVerified, readable = nodes.isNotEmpty(),
            ) ?: reason(service)
            val inputTarget = nodes.filter { it.keyboardKey.isEmpty() && it.sourceWindowId == selected.selection.target &&
                it.sourcePackage == pkg && it.editable && it.enabled && it.focused && it.focusable && !it.password }.singleOrNull()
            val frame = ControlFrame(pkg, window.id, fingerprint, nodes, code, truncated,
                truncationReasons, visited, deepest, touchContext(service, window, windows), root.windowId,
                selected.signature, selected.diagnostics, revision, outputLimit,
                verifiedApplicationPackage = if (code == null && !truncated &&
                    pkg == AndroidGeminiDraftUi.GOOGLE_HOST && geminiIdentityVerified) AndroidGeminiDraftUi.PACKAGE else null,
                keyboard = selected.keyboard, inputTargetId = inputTarget?.id, inputTarget = inputTarget,
                keyboardDiagnostics = selected.keyboard?.takeIf { it.readAllowed }?.let { keyboardScan.json() })
            block(EmptyOwnedSurfacePolicy.restrict(frame,
                selected.selection.members.size > 1 && root.windowId in selected.selection.members &&
                    window.id in selected.selection.members), native)
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
        for (item in windows.filterNot {
            it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && service.ownsRemoteControlIndicatorWindow(it.id)
        }.sortedBy { it.id }) {
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
    private suspend fun tap(service: AgentService, frame: ControlFrame, targetBounds: Rect, targetWindowId: Int = frame.windowId, endBounds: Rect? = null, durationMs: Long = 50, finalGuard: () -> String? = { null }): ControlEffect {
        // Reacquire windows immediately before dispatch, while still on Main. No suspension between
        // these checks and dispatchGesture; the gesture has no caller-controlled coordinates.
        val expectedEpoch = sessionFor(service).epoch
        val windows = service.windows.orEmpty()
        try {
            val root = service.rootInActiveWindow ?: return ControlEffect(false, false, "active_root_missing")
            val rootMatches = try { root.windowId == frame.foregroundWindowId && root.packageName?.toString() == frame.packageName }
                finally { root.recycleCompat() }
            if (!rootMatches) return ControlEffect(false, false, "screen_changed")
            val window = windows.singleOrNull { it.id == targetWindowId }
                ?: return ControlEffect(false, false, "touch_window_unavailable")
            val selected = controlledWindows(service, frame.foregroundWindowId, frame.packageName, windows)
            if (selected.selection.reason != null || selected.selection.target != frame.windowId ||
                selected.signature != frame.windowContext || frame.touchContext.isEmpty() ||
                touchContext(service, windows.single { it.id == frame.windowId }, windows) != frame.touchContext)
                return ControlEffect(false, false, "touch_window_changed")
            val covering = windows.filter { it.id != window.id && it.displayId == window.displayId && it.layer >= window.layer &&
                !(it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && service.ownsRemoteControlIndicatorWindow(it.id)) }
                .map { bounds(windowBounds(it)) }
            val point = NodeTapPolicy.point(bounds(targetBounds), bounds(windowBounds(window)), covering)
                ?: return ControlEffect(false, false, "touch_target_clipped_or_covered")
            val region = Region(); window.getRegionInScreen(region)
            if (!region.contains(point.first, point.second)) return ControlEffect(false, false, "touch_target_outside_region")
            if (service.remoteControlCovers(Rect(point.first, point.second, point.first + 1, point.second + 1))) return ControlEffect(false, false, "control_button_covers_target")
            session()?.refusal(session()?.id, mutation = true)?.let { return ControlEffect(false, false, it) }
            reason(service)?.let { return ControlEffect(false, false, it) }
            mutationBlock(frame.packageName)?.let { return ControlEffect(false, false, it) }
            finalGuard()?.let { return ControlEffect(false, false, it) }
            val end = endBounds?.let { NodeTapPolicy.point(bounds(it), bounds(windowBounds(window)), covering) }
            if (endBounds != null && end == null) return ControlEffect(false, false, "gesture_end_unverified")
            if (end != null) {
                val steps = maxOf(kotlin.math.abs(end.first-point.first), kotlin.math.abs(end.second-point.second))
                if (steps > 4096) return ControlEffect(false, false, "gesture_path_limit")
                for (i in 0..maxOf(1, steps)) {
                    val x = point.first + ((end.first-point.first).toLong()*i/maxOf(1,steps)).toInt()
                    val y = point.second + ((end.second-point.second).toLong()*i/maxOf(1,steps)).toInt()
                    if (!region.contains(x,y) || covering.any { it.contains(x,y) } || service.remoteControlCovers(Rect(x,y,x+1,y+1)))
                        return ControlEffect(false, false, "gesture_path_covered")
                }
            }
            val path = Path().apply { moveTo(point.first.toFloat(), point.second.toFloat()); end?.let { lineTo(it.first.toFloat(),it.second.toFloat()) } }
            val gesture = GestureDescription.Builder().setDisplayId(android.view.Display.DEFAULT_DISPLAY)
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
            val eventEpoch = expectedEpoch
            val timing = currentCoroutineContext()[ControlTiming]
            val gestureId = timing?.nextId() ?: 0
            val step = currentCoroutineContext()[ControlTrace]?.step ?: 0
            return suspendCancellableCoroutine { continuation ->
                // Cancellation can arrive during final Binder reads. A cancelled continuation
                // still enters this block, so guard INSIDE it before any OS side effect.
                if (!continuation.isActive) return@suspendCancellableCoroutine
                val finalRefusal = GestureDispatchGate.refusal(session(), expectedEpoch, continuation.isActive)
                    ?: reason(service) ?: mutationBlock(frame.packageName)
                    ?: if (!packageAllowed(frame.packageName, operation = "interact")) "runtime_interaction_not_allowed" else null
                if (finalRefusal != null) {
                    if (continuation.isActive) continuation.resume(ControlEffect(false, false, finalRefusal))
                    return@suspendCancellableCoroutine
                }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                timing?.mark(ControlTiming.Event.GESTURE_DISPATCH, gestureId, step)
                val submitted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        timing?.mark(ControlTiming.Event.GESTURE_CALLBACK_OK, gestureId, step)
                        if (continuation.isActive) continuation.resume(ControlEffect(true, true, "gesture_completed"))
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        timing?.mark(ControlTiming.Event.GESTURE_CALLBACK_CANCELLED, gestureId, step)
                        if (continuation.isActive) continuation.resume(ControlEffect(true, false, "gesture_cancelled"))
                    }
                }, null)
                if (submitted && sessionFor(service).epoch == eventEpoch)
                    service.showRemoteControlTap(targetBounds, point.first.toFloat(), point.second.toFloat())
                // Even a negative dispatch acknowledgement is conservatively not replayable.
                if (!submitted && continuation.isActive) continuation.resume(ControlEffect(true, false, "gesture_unconfirmed"))
            }
        } finally { windows.forEach { runCatching { it.recycle() } } }
    }
    override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
        val service = AgentService.instance ?: return ControlEffect(false, false, "accessibility_permission_missing")
        val admittedEpoch = sessionFor(service).epoch
        secureWindow(service)?.let { return ControlEffect(false, false, it) }
        val action = input.getValue("action").jsonPrimitive.content
        if (action in setOf("input", "terminal_input", "terminal_submit", "focus", "key", "visual_tap", "visual_long_press", "visual_drag", "visual_scroll", "visual_key")) {
            islandRefusal(input["session_id"]?.jsonPrimitive?.content.orEmpty())?.let {
                return ControlEffect(false, false, it)
            }
        }
        // This is an explicitly planned region from the consumed observation, never a touch.
        // Yield a few frames for the intent cue, then run the full native validation below.
        // No node/effect is retained across this delay and no security check is bypassed.
        if (node != null && !node.password && frame.reason == null && frame.visual?.scope != "display") {
            val previewed = withContext(Dispatchers.Main) {
                val rect = Rect.unflattenFromString(node.bounds)
                if (rect != null && reason(service) == null &&
                    session()?.refusal(session()?.id, mutation = true) == null &&
                    packageAllowed(frame.packageName, operation = "interact")) {
                    service.showRemoteControlTarget(rect); true
                } else false
            }
            if (previewed) {
                controlSpan("intent_cue_wait") { kotlinx.coroutines.delay(96) }
                // FLAG_SECURE may change without tree/geometry changes during the cue.
                // Obtain a NEW screenshot security verdict before the final native boundary.
                secureWindow(service)?.let { return ControlEffect(false, false, it) }
            }
        }
        val effect = controlSpan("fresh_fingerprint_and_action") { withTree(service) { fresh, native ->
            if (sessionFor(service).epoch != admittedEpoch)
                return@withTree ControlEffect(false, false, "control_session_changed_observe_again")
            val failure = GenericControlActionPolicy.refusal(frame, fresh, action)
            if (failure != null) {
                ControlDiagnostics.record("native_validation", failure, metrics = buildJsonObject {
                    put("configuration_changed", frame.configurationRevision != fresh.configurationRevision)
                    put("package_changed", frame.packageName != fresh.packageName)
                    put("window_changed", frame.windowId != fresh.windowId || frame.foregroundWindowId != fresh.foregroundWindowId)
                    put("content_changed", frame.fingerprint != fresh.fingerprint)
                    put("geometry_changed", frame.touchContext != fresh.touchContext)
                    put("truncated", fresh.truncated)
                })
                return@withTree ControlEffect(false, false, failure)
            }
            reason(service)?.let { return@withTree ControlEffect(false, false, it) }
            mutationBlock(fresh.packageName)?.let { return@withTree ControlEffect(false, false, it) }
            session()?.refusal(session()?.id, mutation = true)?.let { return@withTree ControlEffect(false, false, it) }
            actionChangeVersion = service.remoteControlChanges.version()
            if (action == "home" || action == "back") {
                session()?.feedback(if (action == "home") "홈 화면으로 이동하고 있습니다" else "이전 화면으로 이동하고 있습니다", admittedEpoch)
                val result = platform(service).performAction(UIAction.SystemButton(if (action == "home") SystemButtonType.HOME else SystemButtonType.BACK))
                return@withTree ControlEffect(true, result is ActionResult.Success, "system_button_acknowledged")
            }
            if (action == "open_app") {
                val pkg = input["package_name"]?.jsonPrimitive?.content.orEmpty()
                appSelections.refusal(pkg, packageAllowed(pkg, operation = "launch"))?.let {
                    return@withTree ControlEffect(false, false, it)
                }
                session()?.feedback("앱을 실행하고 있습니다", admittedEpoch)
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
            var visualKeyRect: Rect? = null
            if (action.startsWith("visual_")) {
                val proof = frame.visual ?: return@withTree ControlEffect(false, false, "visual_observation_required")
                if (!proof.sourceDisclosureAllowed()) return@withTree ControlEffect(false,false,"visual_source_revoked_or_expired")
                if(!editorGenerationMatches(proof,service) || editorImageRefusal(service,frame.packageName)!=null)
                    return@withTree ControlEffect(false,false,"visual_editor_privacy_changed")
                if (input["visual_id"]?.jsonPrimitive?.content != proof.id || input["visual_target_confirmed"]?.jsonPrimitive?.booleanOrNull != true ||
                    System.currentTimeMillis()-proof.at !in 0L..60_000L) return@withTree ControlEffect(false,false,"visual_proof_invalid_or_expired")
                if (!VisualActionPolicy.scopeAllowed(action,proof.scope)) return@withTree ControlEffect(false,false,"visual_scope_mismatch")
                if (action == "visual_key") VisualKeyboardPolicy.refusal(fresh, fresh.nodes.singleOrNull { it.id == node?.id }, proof.scope)?.let {
                    return@withTree ControlEffect(false,false,it)
                }
                fun rect(prefix: String): Rect? {
                    val a = input[prefix]?.jsonArray ?: return null
                    if (a.size != 4) return null
                    val numbers = a.map { it.jsonPrimitive.intOrNull ?: return null }
                    val mapped = VisualActionPolicy.target(proof,action,TapBounds(numbers[0],numbers[1],numbers[2],numbers[3])) ?: return null
                    return Rect(mapped.left,mapped.top,mapped.right,mapped.bottom)
                }
                val start = rect("image_rect") ?: return@withTree ControlEffect(false,false,"visual_target_bounds_invalid")
                val end = if (action in setOf("visual_drag","visual_scroll")) rect("image_end_rect")
                    ?: return@withTree ControlEffect(false,false,"visual_end_bounds_required") else null
                if (!VisualActionPolicy.pathAllowed(proof,bounds(start),end?.let { bounds(it) }))
                    return@withTree ControlEffect(false,false,"visual_path_pixels_unverified")
                val latest = (if (proof.scope == "display") visualDisplay(fresh) else if (action == "visual_key") visualKeyboard(fresh) else visual(fresh)) ?: return@withTree ControlEffect(false,false,"visual_revalidation_failed")
                if (latest.pixelHash != proof.pixelHash || latest.window != proof.window || latest.context != proof.context || latest.sourceWindowId != proof.sourceWindowId || latest.scope != proof.scope || latest.sourceDisplayId != proof.sourceDisplayId || latest.rotation != proof.rotation || latest.disclosedRegions != proof.disclosedRegions || latest.editorPrivacyGeneration != proof.editorPrivacyGeneration) {
                    ControlDiagnostics.record("visual_revalidation", "visual_pixels_or_geometry_changed", metrics = VisualRevalidationDiagnostics.metrics(proof, latest))
                    return@withTree ControlEffect(false,false,"visual_pixels_or_geometry_changed")
                }
                if (proof.scope == "display" && proof.displayGeneration != service.remoteDisplayChanges.version())
                    return@withTree ControlEffect(false,false,"display_window_changed_since_observation")
                if (action == "visual_key") visualKeyRect = start
                else return@withTree tap(service, fresh, start, endBounds = end,
                    durationMs = if (action == "visual_long_press") 650 else if (end != null) 450 else 50) {
                        if (!proof.sourceDisclosureAllowed()) "visual_source_revoked_or_expired"
                        else if (proof.scope=="display" && proof.displayGeneration!=service.remoteDisplayChanges.version()) "display_window_changed_since_observation"
                        else if(!editorGenerationMatches(proof,service)) "visual_editor_privacy_changed" else editorImageRefusal(service,frame.packageName)
                    }
            }
            val at = fresh.nodes.indexOfFirst { it.id == node?.id }
            val target = native.getOrNull(at) ?: return@withTree ControlEffect(false, false, "target_unavailable")
            val expected = fresh.nodes[at]
            val expectedWindow = if (action in setOf("key", "visual_key")) fresh.keyboard?.windowId ?: -1 else fresh.windowId
            val expectedPackage = if (action in setOf("key", "visual_key")) fresh.keyboard?.packageName.orEmpty() else fresh.packageName
            if (!target.refresh() || !target.isVisibleToUser || !target.isEnabled || target.isPassword || target.windowId != expectedWindow || target.packageName?.toString() != expectedPackage)
                return@withTree ControlEffect(false, false, "target_changed")
            val rect = Rect(); target.getBoundsInScreen(rect)
            val currentText = if (target.isShowingHintText) "" else target.text?.toString().orEmpty().take(2001)
            if (currentText != expected.text || target.isFocused != expected.focused ||
                target.textSelectionStart != expected.selectionStart || target.textSelectionEnd != expected.selectionEnd || target.contentDescription?.toString().orEmpty().take(100) != expected.description ||
                target.viewIdResourceName.orEmpty().take(100) != expected.resourceId || rect.flattenToString() != expected.bounds ||
                (Build.VERSION.SDK_INT >= 34 && target.isAccessibilityDataSensitive))
                return@withTree ControlEffect(false, false, "target_changed")
            // Semantic accessibility actions do not inject a point. Large scroll containers
            // may overlap the panel; only the actual injected tap point is occlusion-gated.
            service.showRemoteControlTarget(rect)
            session()?.feedback(when (action) {
                "scroll" -> "화면을 스크롤하고 있습니다"
                "type", "input" -> "입력란에 글자를 입력하고 있습니다"
                "focus" -> "입력란에 초점을 맞추고 있습니다"
                "expand" -> "선택한 내용을 펼치고 있습니다"
                "collapse" -> "선택한 내용을 접고 있습니다"
                "long_press" -> "선택한 항목의 길게 누르기 기능을 실행합니다"
                else -> "선택한 항목을 누르고 있습니다"
            }, admittedEpoch)
            if (action in setOf("terminal_input", "terminal_submit")) {
                // No Termux screen-selection absence proof exists for this binding.
                return@withTree ControlEffect(false, false, TerminalInputPolicy.selectionRefusal())
                /*                val text = input["text"]?.jsonPrimitive?.content.orEmpty()
                val refusal = if (action == "terminal_submit") TerminalInputPolicy.submitRefusal(expected,text,
                    input["terminal_authorized"]?.jsonPrimitive?.booleanOrNull == true, input["submit_authorized"]?.jsonPrimitive?.booleanOrNull == true, frame.terminalSubmission)
                    else TerminalInputPolicy.refusal(expected,text,input["terminal_authorized"]?.jsonPrimitive?.booleanOrNull == true,
                        input["terminal_force_authorized"]?.jsonPrimitive?.booleanOrNull == true)
                refusal?.let {
                    return@withTree ControlEffect(false, false, it)
                }
                val binding = TerminalInputBinding.capture(service)
                    ?: return@withTree ControlEffect(false, false, "terminal_binding_unverified")
                if (binding.evidence(target) != expected.terminal)
                    return@withTree ControlEffect(false, false, "terminal_target_changed")
                currentCoroutineContext().ensureActive()
                reason(service)?.let { return@withTree ControlEffect(false, false, it) }
                islandRefusal(input["session_id"]?.jsonPrimitive?.content.orEmpty())?.let { return@withTree ControlEffect(false, false, it) }
                return@withTree try {
                    binding.commit(if (action == "terminal_submit") "\n" else text)
                    ControlEffect(true, true, "terminal_input_dispatched")
                } catch (_: Exception) { ControlEffect(true, false, "terminal_input_unknown") }
                */
            }
            if (action in setOf("key", "visual_key")) {
                if (fresh.keyboard == null || !fresh.keyboard.readAllowed || !fresh.keyboard.interactAllowed || fresh.keyboard != frame.keyboard || fresh.inputTargetId == null ||
                    fresh.inputTargetId != frame.inputTargetId || (action == "key" && !target.isTextEntryKey) || expected.keyboardKey.isEmpty())
                    return@withTree ControlEffect(false, false, "keyboard_target_unverified")
                val key = (if (action == "visual_key") VisualKeyboardPolicy.identify(currentText, target.contentDescription?.toString().orEmpty())
                else KeyboardKeyPolicy.identify(currentText, target.contentDescription?.toString().orEmpty(),
                    target.className?.toString().orEmpty(), target.isClickable || target.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }, target.isTextEntryKey))
                    ?: return@withTree ControlEffect(false, false, "keyboard_key_unrecognized")
                if (key.kind == KeyboardKeyPolicy.Kind.SUBMIT && input["submit_authorized"]?.jsonPrimitive?.booleanOrNull != true)
                    return@withTree ControlEffect(false, false, "submission_authorization_required")
                val field = fresh.inputTarget ?: return@withTree ControlEffect(false, false, "keyboard_target_unverified")
                if (field.text.length > 2000 || InputPayloadPolicy.sensitive(field.text) || (field.editable && key.kind in setOf(
                    KeyboardKeyPolicy.Kind.CHARACTER, KeyboardKeyPolicy.Kind.SPACE, KeyboardKeyPolicy.Kind.BACKSPACE) &&
                    KeyboardKeyPolicy.expectedText(field, key) == null)) return@withTree ControlEffect(false, false, "sensitive_input_refused")
                val tapRect = rect
                if (action == "visual_key" && (visualKeyRect == null || !rect.contains(visualKeyRect) || !visualKeyRect.contains(rect.centerX(), rect.centerY()) || VisualKeyboardPolicy.proofOwnerRefusal(frame.visual,fresh.keyboard) != null))
                    return@withTree ControlEffect(false,false,"keyboard_visual_bounds_invalid")
                return@withTree tap(service, fresh, tapRect, fresh.keyboard.windowId) {
                    if (action=="visual_key" && frame.visual?.sourceDisclosureAllowed() != true)
                        return@tap "visual_source_revoked_or_expired"
                    if(action=="visual_key" && (!editorGenerationMatches(frame.visual,service) || editorImageRefusal(service,frame.packageName)!=null))
                        return@tap "visual_editor_privacy_changed"
                    if (action == "visual_key" && frame.visual?.scope == "display" && frame.visual.displayGeneration != service.remoteDisplayChanges.version())
                        return@tap "display_window_changed_since_observation"
                    // Reacquire the actual app input focus after the IME tree scan. Window/IME
                    // stability alone cannot prove that a user did not select a different field.
                    val appWindow = service.windows.orEmpty()
                    var appRoot: AccessibilityNodeInfo? = null
                    var focused: AccessibilityNodeInfo? = null
                    try {
                        appRoot = appWindow.singleOrNull { it.id == field.sourceWindowId }?.root
                        focused = appRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        val live = focused
                        val focusRefusal = if (live == null || !live.refresh() || !live.isVisibleToUser ||
                            (Build.VERSION.SDK_INT >= 34 && live.isAccessibilityDataSensitive)) "keyboard_input_target_changed"
                        else {
                            val liveBounds = Rect(); live.getBoundsInScreen(liveBounds)
                            KeyboardTargetPolicy.refusal(field, field.copy(
                                text = if (live.isShowingHintText) "" else live.text?.toString().orEmpty().take(2001),
                                resourceId = live.viewIdResourceName.orEmpty().take(100), className = live.className?.toString().orEmpty(),
                                bounds = liveBounds.flattenToString(), enabled = live.isEnabled, focused = live.isFocused,
                                focusable = live.isFocusable, editable = live.isEditable, password = live.isPassword,
                                sourceWindowId = live.windowId, sourcePackage = live.packageName?.toString().orEmpty(),
                                selectionStart = live.textSelectionStart, selectionEnd = live.textSelectionEnd))
                        }
                        if (focusRefusal != null) focusRefusal
                        else {
                            val latest = controlledWindows(service, fresh.foregroundWindowId, fresh.packageName, appWindow)
                            val keyBounds = Rect()
                            if (!target.refresh()) "keyboard_key_changed" else {
                                target.getBoundsInScreen(keyBounds)
                                if (latest.selection.reason != null || latest.signature != fresh.windowContext ||
                                    latest.keyboard != fresh.keyboard || !packageAllowed(expectedPackage, operation = "read") ||
                                    !packageAllowed(expectedPackage, operation = "interact") || !target.isVisibleToUser || !target.isEnabled ||
                                    (action == "key" && !target.isTextEntryKey) || target.isPassword || target.windowId != expectedWindow ||
                                    target.packageName?.toString() != expectedPackage || keyBounds != rect ||
                                    target.text?.toString().orEmpty() != expected.text ||
                                    target.contentDescription?.toString().orEmpty().take(100) != expected.description ||
                                    (Build.VERSION.SDK_INT >= 34 && target.isAccessibilityDataSensitive)) "keyboard_key_changed" else null
                            }
                        }
                    } finally {
                        if (focused !== appRoot) focused?.recycleCompat()
                        appRoot?.recycleCompat(); appWindow.forEach { runCatching { it.recycle() } }
                    }
                }
            }
            if (action == "tap") {
                if (!target.isClickable && target.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                    return@withTree ControlEffect(false, false, "target_not_clickable")
                return@withTree tap(service, fresh, rect)
            }
            // Atomic native node actions match the existing Gemini adapter; no focus/tap/retry fallback.
            val acknowledged = when (action) {
                "click" -> {
                    val timing = currentCoroutineContext()[ControlTiming]
                    val clickId = timing?.nextId() ?: 0
                    val step = currentCoroutineContext()[ControlTrace]?.step ?: 0
                    timing?.mark(ControlTiming.Event.NODE_CLICK_DISPATCH, clickId, step)
                    val result = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    timing?.mark(ControlTiming.Event.NODE_CLICK_RETURNED, clickId, step)
                    result
                }
                "expand", "collapse" -> {
                    val nativeAction = if (action == "expand") AccessibilityNodeInfo.ACTION_EXPAND else AccessibilityNodeInfo.ACTION_COLLAPSE
                    if (target.actionList.none { it.id == nativeAction }) return@withTree ControlEffect(false, false, "native_action_unavailable")
                    target.performAction(nativeAction)
                }
                "long_press" -> target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                "scroll" -> {
                    val direction = input.getValue("direction").jsonPrimitive.content
                    val directional = when (direction) {
                        "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                        "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                        "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                        "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                        else -> return@withTree ControlEffect(false, false, "scroll_direction_invalid")
                    }
                    val supported = target.actionList.any { it.id == directional }
                    if (!supported && direction in setOf("left", "right"))
                        return@withTree ControlEffect(false, false, "directional_scroll_unsupported")
                    target.performAction(if (supported) directional else if (direction == "down")
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                }
                "focus" -> {
                    if (!target.isFocusable || target.actionList.none { it.id == AccessibilityNodeInfo.ACTION_FOCUS })
                        return@withTree ControlEffect(false, false, "native_focus_unsupported")
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }
                "input" -> {
                    val plan = UnifiedInputPolicy.plan(expected, input["text"]?.jsonPrimitive?.content.orEmpty(),
                        input["method"]?.jsonPrimitive?.content ?: "auto", input["mode"]?.jsonPrimitive?.content ?: "empty",
                        input["clipboard_consent"]?.jsonPrimitive?.booleanOrNull == true)
                    plan.refusal?.let { return@withTree ControlEffect(false, false, it) }
                    if (!target.isEditable || !target.isFocused) return@withTree ControlEffect(false, false, "focused_editable_required")
                    if (plan.method == "direct") {
                        if (target.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })
                            return@withTree ControlEffect(false, false, "direct_input_unsupported")
                        target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, plan.resultText)
                        })
                    } else {
                        if (target.actionList.none { it.id == AccessibilityNodeInfo.ACTION_PASTE })
                            return@withTree ControlEffect(false, false, "paste_unsupported")
                        if (input["clipboard_replace_authorized"]?.jsonPrimitive?.booleanOrNull != true)
                            return@withTree ControlEffect(false, false, "clipboard_replacement_authorization_required")
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                            ?: return@withTree ControlEffect(false, false, "clipboard_unavailable")
                        val acquisition = OwnedClipboardLease.inspectAndAcquire(clipboard,
                            input.getValue("text").jsonPrimitive.content, replacementAuthorized = true)
                        val lease = acquisition.lease ?: return@withTree ControlEffect(false, false,
                            acquisition.refusal ?: "clipboard_read_access_unproven")
                        // No suspension between final guards and staging. Clipboard access is
                        // re-proven after the write, then full app/window/focus guards run again.
                        currentCoroutineContext().ensureActive()
                        islandRefusal(input["session_id"]?.jsonPrimitive?.content.orEmpty())?.let {
                            return@withTree ControlEffect(false, false, it)
                        }
                        return@withTree ClipboardInputTransaction(
                            stage = lease::stage,
                            pasteWithFreshGuards = guardedPaste@ {
                                secureWindow(service)?.let { return@guardedPaste ControlEffect(false, false, it) }
                                withTree(service) paste@ { latest, latestNative ->
                                    currentCoroutineContext().ensureActive()
                                    if (sessionFor(service).epoch != admittedEpoch)
                                        return@paste ControlEffect(false, false, "control_session_changed_observe_again")
                                    islandRefusal(input["session_id"]?.jsonPrimitive?.content.orEmpty())?.let {
                                        return@paste ControlEffect(false, false, it)
                                    }
                                    GenericControlActionPolicy.refusal(fresh, latest, "input")?.let {
                                        return@paste ControlEffect(false, false, it)
                                    }
                                    mutationBlock(latest.packageName)?.let { return@paste ControlEffect(false, false, it) }
                                    if (!packageAllowed(latest.packageName, operation = "interact"))
                                        return@paste ControlEffect(false, false, "runtime_interaction_not_allowed")
                                    val position = latest.nodes.indexOfFirst { it == expected }
                                    val live = latestNative.getOrNull(position)
                                        ?: return@paste ControlEffect(false, false, "target_changed")
                                    val bounds = Rect()
                                    if (!live.refresh()) return@paste ControlEffect(false, false, "target_changed")
                                    live.getBoundsInScreen(bounds)
                                    if (!live.isFocused || !live.isEditable || !live.isEnabled || !live.isVisibleToUser || live.isPassword ||
                                        live.windowId != expectedWindow || live.packageName?.toString() != expectedPackage ||
                                        live.viewIdResourceName.orEmpty().take(100) != expected.resourceId || bounds.flattenToString() != expected.bounds ||
                                        (if (live.isShowingHintText) "" else live.text?.toString().orEmpty()) != expected.text ||
                                        live.textSelectionStart != expected.selectionStart || live.textSelectionEnd != expected.selectionEnd ||
                                        (Build.VERSION.SDK_INT >= 34 && live.isAccessibilityDataSensitive) ||
                                        live.actionList.none { it.id == AccessibilityNodeInfo.ACTION_PASTE })
                                        return@paste ControlEffect(false, false, "target_changed")
                                    if (!lease.ownsCurrent()) return@paste ControlEffect(false, false, "clipboard_provenance_changed")
                                    val ok = live.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                                    ControlEffect(true, ok, if (ok) "paste_acknowledged" else "paste_outcome_unknown")
                                }
                            },
                            // Android exposes no atomic compare-and-restore. Preserve competing clips.
                            restoreIfOwned = {}
                        ).execute()
                    }
                }
                "type" -> {
                    if (!target.isEditable || (!target.isShowingHintText && !target.text.isNullOrEmpty()))
                        return@withTree ControlEffect(false, false, "draft_not_empty")
                    target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, input.getValue("text").jsonPrimitive.content)
                    })
                }
                else -> return@withTree ControlEffect(false, false, "unsupported_action")
            }
            service.showRemoteControlAction(rect,
                if (action == "scroll") "scroll_${input.getValue("direction").jsonPrimitive.content}" else action,
                acknowledged)
            ControlEffect(true, acknowledged, if (acknowledged) "action_acknowledged" else "action_unconfirmed")
        }
        }
        return effect
    }
}
