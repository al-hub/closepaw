package ai.closepaw.qa

import ai.closepaw.BuildConfig
import ai.closepaw.app.AgentService
import ai.closepaw.chatgpt.McpExecutionSettings
import ai.closepaw.chatgpt.control.AndroidControlPort
import ai.closepaw.chatgpt.control.ControlScreenLease
import ai.closepaw.chatgpt.control.ControlSession
import ai.closepaw.chatgpt.control.ControlFrame
import ai.closepaw.chatgpt.control.ControlWindowContinuity
import ai.closepaw.chatgpt.control.DisplayObservationRecoveryPolicy
import ai.closepaw.chatgpt.control.GenericControl
import ai.closepaw.chatgpt.control.HangulKeyComposer
import ai.closepaw.chatgpt.control.NativeKeyboardStructuralCertification
import ai.closepaw.platform.virtualdisplay.ProjectionImageLease
import ai.closepaw.platform.virtualdisplay.ProjectionImageTransport
import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** Candidate-only locally armed diagnostic. Every actual key goes through
 * production GenericControl + AndroidControlPort native safety checks.
 * No raw ADB, coordinates, text injection, clipboard or submission. */
internal object DebugOneStopQa {
    internal object SixKey {
        val labels = listOf("ㄱ", "ㅏ", "Space", "ㄴ", "ㅏ", "Space")
        fun expected(completed: Int): String {
            require(completed in 0..labels.size)
            return labels.take(completed).fold("") { current, label ->
                if (label == "Space") current + " " else HangulKeyComposer.append(current, label)
            }
        }
        fun matches(text: String, start: Int, end: Int, completed: Int): Boolean {
            val expected = expected(completed)
            return text == expected && start == expected.length && end == expected.length
        }
        fun mayContinue(attempted: Boolean, unknown: Boolean, verified: Boolean) =
            verified && attempted && !unknown
    }

    // Canonical #147 benchmark table (not the conflicting older candidate
    // sentence in the issue's historical progress section).
    internal const val MIXED_29 = "오늘 회의는 zoom으로 3시에 시작, 자료는 2개."
    private const val CHROME = "com.android.chrome"
    private const val SAMSUNG_IME = "com.samsung.android.honeyboard"
    private val INCOGNITO_WINDOW_INDICATOR_IDS = setOf(
        "$CHROME:id/incognito_badge",
        "$CHROME:id/incognito_ntp_container",
        "$CHROME:id/new_tab_incognito_container",
        "$CHROME:id/incognito_tab_indicator",
    )

    /** Content-free native readiness facts; these do not relax native action guards. */
    internal data class EditorPreflightFacts(
        val frameReady: Boolean, val complete: Boolean, val navigationSafe: Boolean,
        val chromeForeground: Boolean, val keyboardPresent: Boolean, val samsungIme: Boolean,
        val keyboardReadAllowed: Boolean, val keyboardInteractAllowed: Boolean,
        val editorPresent: Boolean, val chromeEditorOwned: Boolean,
        val editorFocused: Boolean, val editorEditable: Boolean,
        val nonPassword: Boolean, val editorEmpty: Boolean, val selectionAtOrigin: Boolean,
    ) {
        fun redactedJson() = buildJsonObject {
            put("frame_ready", frameReady); put("frame_complete", complete)
            put("navigation_safe", navigationSafe); put("chrome_foreground", chromeForeground)
            put("keyboard_present", keyboardPresent); put("samsung_ime", samsungIme)
            put("keyboard_read_allowed", keyboardReadAllowed)
            put("keyboard_interact_allowed", keyboardInteractAllowed)
            put("editor_present", editorPresent); put("chrome_editor_owned", chromeEditorOwned)
            put("editor_focused", editorFocused); put("editor_editable", editorEditable)
            put("non_password", nonPassword); put("editor_empty", editorEmpty)
            put("selection_at_origin", selectionAtOrigin)
        }
    }

    internal fun editorPreflightFacts(frame: ControlFrame): EditorPreflightFacts {
        val keyboard = frame.keyboard
        val editor = frame.inputTarget
        return EditorPreflightFacts(
            frameReady = frame.reason == null, complete = !frame.truncated,
            navigationSafe = !frame.navigationOnly, chromeForeground = frame.packageName == CHROME,
            keyboardPresent = keyboard != null, samsungIme = keyboard?.packageName == SAMSUNG_IME,
            keyboardReadAllowed = keyboard?.readAllowed == true,
            keyboardInteractAllowed = keyboard?.interactAllowed == true,
            editorPresent = editor != null, chromeEditorOwned = editor?.sourcePackage == CHROME,
            editorFocused = editor?.focused == true, editorEditable = editor?.editable == true,
            nonPassword = editor != null && !editor.password,
            editorEmpty = editor?.text?.isEmpty() == true,
            selectionAtOrigin = editor?.selectionStart == 0 && editor.selectionEnd == 0,
        )
    }

    internal fun editorPreflightReason(f: EditorPreflightFacts): String? = when {
        !f.frameReady -> "preflight_frame_unavailable"
        !f.complete -> "preflight_frame_truncated"
        !f.navigationSafe -> "preflight_navigation_only"
        !f.chromeForeground -> "preflight_chrome_not_foreground"
        !f.keyboardPresent -> "preflight_keyboard_missing"
        !f.samsungIme -> "preflight_samsung_ime_missing"
        !f.keyboardReadAllowed -> "preflight_keyboard_read_denied"
        !f.keyboardInteractAllowed -> "preflight_keyboard_interact_denied"
        !f.editorPresent -> "preflight_editor_missing"
        !f.chromeEditorOwned -> "preflight_editor_owner_mismatch"
        !f.editorFocused -> "preflight_editor_not_focused"
        !f.editorEditable -> "preflight_editor_not_editable"
        !f.nonPassword -> "preflight_password_editor"
        !f.editorEmpty -> "preflight_editor_not_empty"
        !f.selectionAtOrigin -> "preflight_selection_not_origin"
        else -> null
    }

    /** A fresh full native security scan is allowed once for a transient invalid window.
     * Never retry a proven secure window, an unknown failure, or any dispatched action. */
    internal fun readOnlyResampleReason(reason: String?): Boolean =
        // A root/window change during traversal is a read-only race, not an
        // authorization to replay any action. Only one fresh capture is allowed.
        reason == "active_root_missing" || reason == "secure_window_changed" ||
            reason == "screen_changed"



    /** P0 regression: Chromium can report a Chrome root before its ACTIVE+FOCUSED
     * AccessibilityWindowInfo settles. Native secure-window refusal remains fatal.
     * This gate permits ONLY fresh read-only captures; never action replay. */
    internal fun mayResampleChromeTransition(reason: String?, chromeRoot: Boolean, reads: Int): Boolean =
        reads in 1..2 && (readOnlyResampleReason(reason) ||
            (reason == "foreground_application_required" && chromeRoot))

    /** Content-free facts from the *same* OS window identity that native
     * ControlWindowPolicy requires, for diagnosing foreground transition races.
     * Never saved: URLs, editor text, node bounds, pixels, or another app's name. */
    internal data class ChromeWindowFacts(
        val chromeRoot: Boolean, val rootWindowFound: Boolean,
        val applicationWindow: Boolean, val active: Boolean, val focused: Boolean,
        val chromeOwned: Boolean, val rootPresent: Boolean, val pictureInPicture: Boolean,
    ) {
        fun redactedJson() = buildJsonObject {
            put("chrome_root", chromeRoot)
            put("root_window_found", rootWindowFound)
            put("application_window", applicationWindow)
            put("active", active)
            put("focused", focused)
            put("chrome_owned", chromeOwned)
            put("accessibility_root_present", rootPresent)
            put("picture_in_picture", pictureInPicture)
        }
    }

    private suspend fun chromeWindowFacts(): ChromeWindowFacts = withContext(Dispatchers.Main) {
        val service = AgentService.instance
        val node = service?.rootInActiveWindow
        val rootWindowId = node?.windowId
        val chromeRoot = try { node?.packageName?.toString() == CHROME } finally { node?.recycle() }
        val window = service?.windows.orEmpty().singleOrNull { it.id == rootWindowId }
        val windowRoot = window?.root
        val chromeOwned = try { windowRoot?.packageName?.toString() == CHROME }
            finally { windowRoot?.recycle() }
        ChromeWindowFacts(chromeRoot, window != null,
            window?.type == AccessibilityWindowInfo.TYPE_APPLICATION,
            window?.isActive == true, window?.isFocused == true,
            chromeOwned, windowRoot != null, window?.isInPictureInPictureMode == true)
    }

    internal data class ChromeCaptureSample(
        val frame: ControlFrame, val reads: Int, val recoveryReads: Int,
        val firstRefusal: String?, val windowFactsAtRefusal: ChromeWindowFacts?,
    )

    /** Three full native reads TOTAL per preflight boundary (initial + two
     * renewals). Every renewal must still have the user's live authority;
     * even an apparent success is freshly validated by normal native guards. */
    internal suspend fun captureChromeTransition(
        capture: suspend () -> ControlFrame,
        inspect: suspend () -> ChromeWindowFacts,
        allowed: () -> Boolean,
        pause: suspend () -> Unit,
        onRetry: (String) -> Unit = {},
        needsToolbar: (ControlFrame) -> Boolean = { false },
    ): ChromeCaptureSample {
        var frame = capture()
        var reads = 1
        var retries = 0
        var firstRefusal = frame.reason
        var facts: ChromeWindowFacts? = null
        var toolbarBaseline: ControlFrame? = null
        while (reads < 3 && allowed()) {
            val refusal = frame.reason
            val retryReason = if (refusal != null) {
                if (firstRefusal == null) firstRefusal = refusal
                facts = inspect()
                if (!mayResampleChromeTransition(refusal, facts.chromeRoot, reads)) break
                refusal
            } else {
                if (!safeChromeFrame(frame) || !needsToolbar(frame)) break
                if (toolbarBaseline == null) toolbarBaseline = frame
                "chrome_toolbar_settling"
            }
            onRetry(retryReason)
            pause()
            if (!allowed()) break
            frame = capture()
            reads++
            retries++
            if (firstRefusal == null && frame.reason != null) firstRefusal = frame.reason
            if (toolbarBaseline != null && frame.reason == null && (
                    frame.packageName != toolbarBaseline.packageName ||
                    frame.windowId != toolbarBaseline.windowId ||
                    frame.configurationRevision != toolbarBaseline.configurationRevision
                )) {
                frame = frame.copy(reason = "chrome_toolbar_context_changed")
                break
            }
        }
        return ChromeCaptureSample(frame, reads, retries, firstRefusal, facts)
    }

    /** Native complete Chrome frame only; no content or URL is exported. */
    private fun safeChromeFrame(frame: ControlFrame): Boolean =
        frame.reason == null && !frame.truncated && !frame.navigationOnly && frame.packageName == CHROME

    private fun ownedChromeNodes(frame: ControlFrame) = frame.nodes.filter {
        it.sourcePackage == CHROME && it.sourceWindowId == frame.windowId
    }

    internal data class ChromeSelectorFacts(
        val fullFrame: Boolean, val chromeNodeCount: Int, val urlBarCount: Int,
        val searchBoxCount: Int, val clickableCount: Int, val editableCount: Int,
        val enabledCount: Int, val passwordCount: Int, val blankCount: Int,
        val menuIdCount: Int, val menuClickableCount: Int, val menuEnabledCount: Int,
        val menuEligibleCount: Int, val menuForeignOwnerCount: Int,
        val menuSemanticCount: Int, val tabSwitcherCount: Int,
        val focusedUrlBarCount: Int, val keyboardPresent: Boolean,
        val omniboxSuggestionsPresent: Boolean = false,
    ) {
        fun redactedJson() = buildJsonObject {
            put("full_frame", fullFrame); put("chrome_owned_nodes", chromeNodeCount)
            put("url_bar_matches", urlBarCount); put("search_box_matches", searchBoxCount)
            put("clickable_matches", clickableCount); put("editable_matches", editableCount)
            put("enabled_matches", enabledCount); put("password_matches", passwordCount)
            put("blank_matches", blankCount)
            put("duplicate_candidates", urlBarCount + searchBoxCount > 1)
            put("menu_id_matches", menuIdCount)
            put("menu_clickable_matches", menuClickableCount)
            put("menu_enabled_matches", menuEnabledCount)
            put("menu_eligible_matches", menuEligibleCount)
            put("menu_foreign_owner_or_window_matches", menuForeignOwnerCount)
            put("menu_semantic_matches", menuSemanticCount)
            put("tab_switcher_id_matches", tabSwitcherCount)
            put("focused_url_bar_matches", focusedUrlBarCount)
            put("keyboard_present", keyboardPresent)
            put("omnibox_suggestions_present", omniboxSuggestionsPresent)
        }
    }

    /** Structural counters only: no text, URL, screenshots or pixel logging. */
    internal fun chromeSelectorFacts(frame: ControlFrame): ChromeSelectorFacts {
        val owned = ownedChromeNodes(frame)
        val bars = owned.filter { it.resourceId == "$CHROME:id/url_bar" }
        val search = owned.filter { it.resourceId == "$CHROME:id/search_box_text" }
        val candidates = bars + search
        val menuId = "$CHROME:id/menu_button"
        val menu = owned.filter { it.resourceId == menuId }
        val foreignMenu = frame.nodes.count {
            it.resourceId == menuId &&
                (it.sourcePackage != CHROME || it.sourceWindowId != frame.windowId)
        }
        return ChromeSelectorFacts(safeChromeFrame(frame), owned.size, bars.size, search.size,
            candidates.count { it.clickable }, candidates.count { it.editable },
            candidates.count { it.enabled }, candidates.count { it.password },
            candidates.count { it.empty && it.text.isEmpty() },
            menu.size, menu.count { it.clickable }, menu.count { it.enabled },
            menu.count { it.clickable && it.enabled && !it.password }, foreignMenu,
            owned.count { it.description.trim() in menuDescriptions },
            owned.count { it.resourceId == "$CHROME:id/tab_switcher_button" },
            bars.count { it.focused }, frame.keyboard != null,
            owned.any { it.resourceId == "$CHROME:id/omnibox_suggestions_dropdown" })
    }

    /** No navigation or editing in diagnostic mode when a complete, fresh
     * native frame already proves a Chrome-owned, focused, empty 0/0 editor.
     * Six-key mode still requires creating a fresh new tab first. */
    internal fun mayUseExistingBlankEditorForReadOnly(
        facts: EditorPreflightFacts, diagnosticOnly: Boolean): Boolean =
        diagnosticOnly && editorPreflightReason(facts) == null

    /** Verified Chrome tab-switcher accessibility role; read-only counter. */
    internal fun chromeTabCount(frame: ControlFrame): Int? {
        if (!safeChromeFrame(frame)) return null
        val button = ownedChromeNodes(frame).singleOrNull {
            it.resourceId == "$CHROME:id/tab_switcher_button" &&
                it.enabled && it.clickable && !it.password
        } ?: return null
        val label = button.description
        if (!label.contains("탭") && !label.contains("tab", ignoreCase = true)) return null
        return Regex("\\d+").findAll(label).mapNotNull { it.value.toIntOrNull() }
            .singleOrNull()?.takeIf { it in 0..9998 }
    }

    private val menuDescriptions = setOf("More options", "더보기")

    internal fun chromeMenuButton(frame: ControlFrame): ai.closepaw.chatgpt.control.ControlNode? {
        if (!safeChromeFrame(frame)) return null
        val eligible = ownedChromeNodes(frame).filter { it.clickable && it.enabled && !it.password }
        // Native resource ID remains preferred. Chrome versions can omit the ID
        // while exposing a unique, explicitly named accessibility control.
        val byId = ownedChromeNodes(frame).filter { it.resourceId == "$CHROME:id/menu_button" }
        if (byId.isNotEmpty()) return byId.singleOrNull()?.takeIf { it in eligible }
        return eligible.filter { it.description.trim() in menuDescriptions }.singleOrNull()
    }

    /** Never select incognito, tab group, history or delete command. */
    internal fun chromeNewTabMenuItem(frame: ControlFrame): ai.closepaw.chatgpt.control.ControlNode? {
        if (!safeChromeFrame(frame)) return null
        val owned = ownedChromeNodes(frame)
        val labels = setOf("새 탭", "New tab")
        val identified = owned.filter {
            it.resourceId == "$CHROME:id/new_tab_menu_id" &&
                it.clickable && it.enabled && !it.password
        }
        if (identified.isNotEmpty()) {
            val item = identified.singleOrNull() ?: return null
            return item.takeIf { candidate ->
                owned.count {
                    it.parent == candidate.id && it.resourceId == "$CHROME:id/menu_item_text" &&
                        it.text in labels && !it.password
                } == 1
            }
        }
        // In Chrome versions without resource IDs, accept ONLY the uniquely
        // named enabled regular New Tab command (not Incognito or tab groups).
        // The caller opens a verified Chrome menu first and re-observes it.
        return owned.filter {
            it.clickable && it.enabled && !it.password &&
                (it.text.trim() in labels || it.description.trim() in labels)
        }.singleOrNull()
    }

    /** Only after the tab count independently proves a newly created tab. */
    internal fun blankOmnibox(frame: ControlFrame): ai.closepaw.chatgpt.control.ControlNode? {
        if (!safeChromeFrame(frame)) return null
        return ownedChromeNodes(frame).filter { node ->
            node.resourceId in setOf("$CHROME:id/url_bar", "$CHROME:id/search_box_text") &&
                node.clickable && node.enabled && !node.password &&
                node.empty && node.text.isEmpty()
            // Pre-focus Chrome NTP search_box_text may not be editable yet.
            // Editable, focused, empty caret and Samsung IME checks follow click.
        }.singleOrNull()
    }

    internal fun verifiedBlankNewTab(priorTabCount: Int, frame: ControlFrame): Boolean =
        chromeTabCount(frame) == priorTabCount + 1 && blankOmnibox(frame) != null

    /** Independent before/after evidence after the acknowledged regular New Tab action. */
    internal fun verifiedBlankAfterNewTabCommand(
        before: ControlFrame, frame: ControlFrame, newTabCommandConfirmed: Boolean,
    ): Boolean {
        if (!newTabCommandConfirmed || !sameChromeWindow(before, frame)) return false
        val prior = chromeTabCount(before)
        val after = chromeTabCount(frame)
        if (prior != null && after != null) return verifiedBlankNewTab(prior, frame)
        val ntp = "$CHROME:id/search_box_text"
        return blankOmnibox(frame)?.resourceId == ntp &&
            ownedChromeNodes(before).none { it.resourceId == ntp }
    }

    internal fun sameChromeWindow(before: ControlFrame, after: ControlFrame): Boolean =
        safeChromeFrame(before) && safeChromeFrame(after) &&
            before.windowId == after.windowId &&
            before.configurationRevision == after.configurationRevision

    /**
     * Post-Back blank-surface gate. Android Accessibility API exposes no native Chrome tab ID
     * while the omnibox is focused (tab_switcher_button is hidden), so this gate never claims
     * independent tab-ID proof: it strictly refuses non-blank webpages and requires a restored
     * blank toolbar when [requireToolbarRestored] is true (after Back #2). Any key input still
     * requires opening and verifying a fresh New Tab afterwards.
     */
    private fun isPriorBenchmarkToolbarOmnibox(
        after: ControlFrame,
        priorVerifiedBenchmarkText: String?,
    ): Boolean {
        val expected = SixKey.expected(6)
        if (priorVerifiedBenchmarkText != expected) return false
        if (!safeChromeFrame(after) || isIncognitoChromeWindow(after)) return false
        val bar = ownedChromeNodes(after)
            .filter { it.resourceId == "$CHROME:id/url_bar" }.singleOrNull() ?: return false
        return !bar.focused && !bar.password && bar.text == expected
    }

    private fun isValidPostBackChromeState(
        after: ControlFrame,
        requireToolbarRestored: Boolean = false,
        priorVerifiedBenchmarkText: String? = null,
    ): Boolean {
        val toolbarRestored = chromeMenuButton(after) != null &&
            (blankOmnibox(after) != null || isPriorBenchmarkToolbarOmnibox(after, priorVerifiedBenchmarkText))
        return if (requireToolbarRestored) toolbarRestored
        else toolbarRestored || isGuardedBlankFocusedOmnibox(after) ||
            isGuardedPriorBenchmarkFocusedOmnibox(after, priorVerifiedBenchmarkText)
    }

    /**
     * Classifies post-Back Chrome context continuity using internal-only ControlWindowSnapshot
     * cryptographically bound to ControlFrame.windowContext (covering every signature input:
     * projected ControlWindow including PiP/package/layer, windowType, and screen bounds).
     * Pre-dispatch checks remain unchanged and require exact windowContext + full-screen fingerprint.
     * Post-dispatch, Back without IME dismissal strictly requires identical windowContext and snapshot.
     */
    internal fun chromeBackContextRefusal(
        before: ControlFrame,
        after: ControlFrame,
        requireToolbarRestored: Boolean = false,
        priorVerifiedBenchmarkText: String? = null,
    ): String? {
        if (!sameChromeWindow(before, after)) return "chrome_window_or_revision_changed"
        if (before.foregroundWindowId != before.windowId || after.foregroundWindowId != after.windowId)
            return "foreground_window_changed"
        if (isIncognitoChromeWindow(after)) return "incognito_window_detected"
        if (before.windowContext.isBlank() || after.windowContext.isBlank()) return "window_context_missing"
        val bKb = before.keyboard
        val aKb = after.keyboard
        return when {
            bKb != null && aKb == null -> {
                ControlWindowContinuity.imeDismissalRefusal(before, after, bKb.windowId, CHROME)
                    ?: if (isValidPostBackChromeState(after, requireToolbarRestored, priorVerifiedBenchmarkText)) null
                    else "unexpected_post_back_chrome_state"
            }
            aKb != null && aKb != bKb -> "unexpected_ime_transition"
            before.windowContext == after.windowContext -> {
                ControlWindowContinuity.exactSnapshotRefusal(before, after, CHROME)
                    ?: if (isValidPostBackChromeState(after, requireToolbarRestored, priorVerifiedBenchmarkText)) null
                    else "unexpected_post_back_chrome_state"
            }
            else -> "unexpected_window_context_change"
        }
    }

    internal fun sameChromeBackContext(
        before: ControlFrame,
        after: ControlFrame,
        priorVerifiedBenchmarkText: String? = null,
    ): Boolean =
        chromeBackContextRefusal(before, after, priorVerifiedBenchmarkText = priorVerifiedBenchmarkText) == null

    internal fun hasChromeOmniboxSuggestions(frame: ControlFrame): Boolean =
        safeChromeFrame(frame) && ownedChromeNodes(frame).any {
            it.resourceId == "$CHROME:id/omnibox_suggestions_dropdown"
        }

    /** Ordinary NTP may expose a '새 시크릿 탭' button; require active incognito window indicators only. */
    internal fun isIncognitoChromeWindow(frame: ControlFrame): Boolean =
        ownedChromeNodes(frame).any { it.resourceId in INCOGNITO_WINDOW_INDICATOR_IDS }

    /** Strict gate for guarded Back normalization: ordinary Chrome, no toolbar/menu,
     * single blank focused editable url_bar with no user draft, and same foreground window. */
    internal fun isGuardedBlankFocusedOmnibox(frame: ControlFrame): Boolean {
        if (!safeChromeFrame(frame) || frame.windowId != frame.foregroundWindowId) return false
        if (isIncognitoChromeWindow(frame)) return false
        if (chromeMenuButton(frame) != null || chromeTabCount(frame) != null) return false
        val owned = ownedChromeNodes(frame)
        if (owned.any { it.resourceId == "$CHROME:id/search_box_text" }) return false
        val bar = owned.filter { it.resourceId == "$CHROME:id/url_bar" }.singleOrNull() ?: return false
        if (!bar.focused || !bar.editable || !bar.enabled || bar.password || !bar.empty || bar.text.isNotEmpty())
            return false
        val editor = frame.inputTarget
        if (frame.keyboard != null && editor == null) return false
        if (editor != null && (editor.sourcePackage != CHROME || editor.sourceWindowId != frame.windowId ||
                editor.resourceId != "$CHROME:id/url_bar" || !editor.focused || !editor.editable ||
                editor.password || editor.text.isNotEmpty())) return false
        return true
    }

    /**
     * Narrow Repetition #2/#3 gate ONLY when immediately preceded by an in-batch
     * verified PASS of the exact 6-key benchmark ("가 나 ", caret 4/4).
     * Never weakens [isGuardedBlankFocusedOmnibox] and never touches arbitrary user drafts.
     */
    internal fun isGuardedPriorBenchmarkFocusedOmnibox(
        frame: ControlFrame,
        priorVerifiedBenchmarkText: String?,
    ): Boolean {
        val expected = SixKey.expected(6)
        if (priorVerifiedBenchmarkText != expected) return false
        if (!safeChromeFrame(frame) || frame.windowId != frame.foregroundWindowId) return false
        if (isIncognitoChromeWindow(frame)) return false
        if (chromeMenuButton(frame) != null || chromeTabCount(frame) != null) return false
        val owned = ownedChromeNodes(frame)
        if (owned.any { it.resourceId == "$CHROME:id/search_box_text" }) return false
        val bar = owned.filter { it.resourceId == "$CHROME:id/url_bar" }.singleOrNull() ?: return false
        val expectedLen = expected.length
        if (!bar.focused || !bar.editable || !bar.enabled || bar.password ||
            bar.text != expected || bar.selectionStart != expectedLen || bar.selectionEnd != expectedLen)
            return false
        val editor = frame.inputTarget ?: return false
        if (editor.sourcePackage != CHROME || editor.sourceWindowId != frame.windowId ||
            editor.resourceId != "$CHROME:id/url_bar" || !editor.focused || !editor.editable ||
            editor.password || editor.text != expected ||
            editor.selectionStart != expectedLen || editor.selectionEnd != expectedLen) return false
        return true
    }

    /** Back #1 requires active IME or open omnibox suggestions dropdown.
     * Back #2 requires a verified distinct state transition: IME hidden by Back #1
     * on the same Chrome window/revision/context with focused omnibox + suggestions retained. */
    internal fun mayDispatchGuardedOmniboxBack(
        frame: ControlFrame,
        priorBackFrame: ControlFrame? = null,
        priorVerifiedBenchmarkText: String? = null,
    ): Boolean {
        val eligible = isGuardedBlankFocusedOmnibox(frame) ||
            isGuardedPriorBenchmarkFocusedOmnibox(frame, priorVerifiedBenchmarkText)
        if (!eligible) return false
        return if (priorBackFrame == null) {
            frame.keyboard != null || hasChromeOmniboxSuggestions(frame)
        } else {
            sameChromeBackContext(priorBackFrame, frame, priorVerifiedBenchmarkText) &&
                priorBackFrame.keyboard != null && frame.keyboard == null &&
                hasChromeOmniboxSuggestions(frame)
        }
    }

    internal data class ChromeActionOutcome(
        val confirmed: Boolean, val attempted: Boolean, val unknown: Boolean,
    )

    /** Revalidates guarded-omnibox invariants on the exact fresh observation used for Back. */
    internal fun guardedBackObservationMatches(
        frame: ControlFrame,
        observed: JsonObject,
        priorVerifiedBenchmarkText: String? = null,
    ): Boolean {
        val blankMode = isGuardedBlankFocusedOmnibox(frame)
        val priorBenchmarkMode = !blankMode &&
            isGuardedPriorBenchmarkFocusedOmnibox(frame, priorVerifiedBenchmarkText)
        if (!blankMode && !priorBenchmarkMode) return false
        val expectedBar = ownedChromeNodes(frame)
            .filter { it.resourceId == "$CHROME:id/url_bar" }.singleOrNull() ?: return false
        val navActions = (observed["navigation_actions"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        if (observed["observation_id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() ||
            observed["output_truncated"]?.jsonPrimitive?.booleanOrNull != false ||
            observed["security_scan_complete"]?.jsonPrimitive?.booleanOrNull != true ||
            observed["truncated"]?.jsonPrimitive?.booleanOrNull != false ||
            observed["navigation_only"]?.jsonPrimitive?.booleanOrNull != false ||
            observed["node_actions_allowed"]?.jsonPrimitive?.booleanOrNull != true ||
            observed["package_name"]?.jsonPrimitive?.contentOrNull != CHROME ||
            observed["window_id"]?.jsonPrimitive?.intOrNull != frame.windowId ||
            observed["foreground_window_id"]?.jsonPrimitive?.intOrNull != frame.windowId ||
            observed["configuration_revision"]?.jsonPrimitive?.longOrNull != frame.configurationRevision ||
            "back" !in navActions) return false
        val observedKeyboard = observed["keyboard"] as? JsonObject
        val expectedKeyboard = frame.keyboard
        if (expectedKeyboard != null) {
            if (observedKeyboard == null ||
                observedKeyboard["window_id"]?.jsonPrimitive?.intOrNull != expectedKeyboard.windowId ||
                observedKeyboard["package_name"]?.jsonPrimitive?.contentOrNull != expectedKeyboard.packageName)
                return false
        } else if (observedKeyboard != null) {
            return false
        }
        val bars = (observed["nodes"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.filter {
                it["source_window_id"]?.jsonPrimitive?.intOrNull == frame.windowId &&
                    it["resource_id"]?.jsonPrimitive?.contentOrNull == "$CHROME:id/url_bar"
            }.orEmpty()
        val liveBar = bars.singleOrNull() ?: return false
        val textMatches = if (blankMode) {
            liveBar["text"]?.jsonPrimitive?.contentOrNull == ""
        } else {
            val expectedText = SixKey.expected(6)
            liveBar["text"]?.jsonPrimitive?.contentOrNull == expectedText &&
                liveBar["selection_start"]?.jsonPrimitive?.intOrNull == expectedText.length &&
                liveBar["selection_end"]?.jsonPrimitive?.intOrNull == expectedText.length
        }
        return liveBar["focused"]?.jsonPrimitive?.booleanOrNull == true &&
            liveBar["editable"]?.jsonPrimitive?.booleanOrNull == true &&
            liveBar["enabled"]?.jsonPrimitive?.booleanOrNull == true &&
            textMatches &&
            liveBar["bounds"]?.jsonPrimitive?.contentOrNull == expectedBar.bounds &&
            liveBar["class_name"]?.jsonPrimitive?.contentOrNull == expectedBar.className
    }

    /** Candidate-only preparation, separated from the key coroutine state machine.
     * Every action uses GenericControl's original native freshness/privacy guards. */
    internal data class ChromeReady(
        val frame: ControlFrame? = null, val failure: String? = null,
        val nativeRefusal: String? = null, val diagnostics: ChromeSelectorFacts? = null,
        val tabsBefore: Int? = null, val tabsAfter: Int? = null,
        val newTabVerified: Boolean = false, val newTabEvidence: String = "none",
        val navigationClicks: Int = 0,
        val focusClicks: Int = 0, val unknown: Boolean = false,
        val chromeReadCount: Int = 0, val chromeRecoveryReads: Int = 0,
        val firstChromeRefusal: String? = null,
        val foregroundFactsAtRefusal: ChromeWindowFacts? = null,
        val toolbarSamples: List<ChromeSelectorFacts> = emptyList(),
        val omniboxBackAttempts: Int = 0,
        val backContextRefusalDetail: String? = null,
        val backStepFacts: List<JsonObject> = emptyList(),
    )

    internal suspend fun prepareChromeEditorWithSeam(
        capture: suspend () -> ControlFrame,
        inspectWindow: suspend () -> ChromeWindowFacts,
        clickNode: suspend (ControlFrame, ai.closepaw.chatgpt.control.ControlNode, String) -> ChromeActionOutcome,
        dispatchBack: suspend (ControlFrame, String) -> ChromeActionOutcome,
        allowed: () -> Boolean,
        diagnosticOnly: Boolean,
        onPhase: (String) -> Unit,
        pause: suspend (Long) -> Unit = { delay(it) },
        priorVerifiedBenchmarkText: String? = null,
    ): ChromeReady {
        var nativeRefusal: String? = null
        var diagnostics: ChromeSelectorFacts? = null
        var tabsBefore: Int? = null
        var tabsAfter: Int? = null
        var newTabVerified = false
        var newTabEvidence = "none"
        var navigationClicks = 0
        var focusClicks = 0
        var unknown = false
        var chromeReadCount = 0
        var chromeRecoveryReads = 0
        var firstChromeRefusal: String? = null
        var foregroundFactsAtRefusal: ChromeWindowFacts? = null
        val toolbarSamples = mutableListOf<ChromeSelectorFacts>()
        var omniboxBackAttempts = 0
        var backContextRefusalDetail: String? = null
        val backStepFacts = mutableListOf<JsonObject>()
        fun result(frame: ControlFrame? = null, failure: String? = null) = ChromeReady(
            frame, failure, nativeRefusal, diagnostics, tabsBefore, tabsAfter,
            newTabVerified, newTabEvidence, navigationClicks, focusClicks, unknown,
            chromeReadCount, chromeRecoveryReads, firstChromeRefusal, foregroundFactsAtRefusal,
            toolbarSamples, omniboxBackAttempts, backContextRefusalDetail, backStepFacts)

        suspend fun settleChromeTransition(seekToolbar: Boolean = false): ControlFrame {
            val sample = captureChromeTransition(
                capture = {
                    capture().also { if (seekToolbar) toolbarSamples += chromeSelectorFacts(it) }
                },
                inspect = inspectWindow,
                allowed = allowed,
                pause = { pause(350L) },
                onRetry = { reason ->
                    onPhase(if (reason == "chrome_toolbar_settling") "chrome_toolbar_read_only_retry"
                        else "chrome_foreground_read_only_retry")
                },
                needsToolbar = { frame ->
                    seekToolbar && chromeMenuButton(frame) == null &&
                        !mayUseExistingBlankEditorForReadOnly(editorPreflightFacts(frame), diagnosticOnly)
                },
            )
            chromeReadCount += sample.reads
            chromeRecoveryReads += sample.recoveryReads
            if (firstChromeRefusal == null) firstChromeRefusal = sample.firstRefusal
            sample.windowFactsAtRefusal?.let { foregroundFactsAtRefusal = it }
            nativeRefusal = sample.frame.reason
            return sample.frame
        }

        // A Chrome root can appear before its native window is active/focused.
        // The read-only bounded settle gate does NOT authorize an action.
        var first = settleChromeTransition(seekToolbar = true)
        if (!allowed()) return result(failure = "control_authority_revoked")
        diagnostics = chromeSelectorFacts(first)
        if (first.reason != null) return result(failure =
            if (first.reason == "chrome_toolbar_context_changed") "chrome_toolbar_context_changed"
            else "preflight_frame_unavailable")
        if (first.truncated) return result(failure = "preflight_frame_truncated")
        if (first.navigationOnly) return result(failure = "preflight_navigation_only")
        if (first.packageName != CHROME) return result(failure = "preflight_chrome_not_foreground")

        suspend fun runClick(
            frame: ControlFrame, target: ai.closepaw.chatgpt.control.ControlNode,
            phase: String, editorFocus: Boolean = false,
        ): Boolean {
            if (!safeChromeFrame(frame) || target.sourcePackage != CHROME ||
                target.sourceWindowId != frame.windowId || !target.clickable ||
                !target.enabled || target.password) return false
            val outcome = clickNode(frame, target, phase)
            if (outcome.attempted) {
                if (editorFocus) focusClicks++ else navigationClicks++
            }
            unknown = unknown || outcome.unknown || (outcome.attempted && !outcome.confirmed)
            return outcome.confirmed && !outcome.unknown
        }

        // This branch is strictly read-only, so there is no reason to navigate
        // away from an already empty, safe Chrome editor. It cannot authorize keys.
        if (mayUseExistingBlankEditorForReadOnly(editorPreflightFacts(first), diagnosticOnly)) {
            newTabEvidence = "existing_empty_editor_read_only"
            return result(frame = first)
        }

        // Guarded omnibox normalization (six-key mode only, max 2 distinct states):
        // normal toolbar -> 0 Back; only proven ordinary Chrome empty focused omnibox
        // (or immediately preceded same-batch PASS benchmark text) with active IME or
        // suggestions dropdown may dismiss focus to reveal toolbar.
        if (!diagnosticOnly && chromeMenuButton(first) == null &&
            mayDispatchGuardedOmniboxBack(first, null, priorVerifiedBenchmarkText)) {
            var priorBackFrame: ControlFrame? = null
            for (step in 1..2) {
                if (!allowed()) return result(failure = "control_authority_revoked")
                if (!mayDispatchGuardedOmniboxBack(first, priorBackFrame, priorVerifiedBenchmarkText)) break
                val beforeBack = first
                val outcome = dispatchBack(beforeBack, "chrome_omnibox_back_${step}_dispatch")
                if (outcome.attempted) {
                    omniboxBackAttempts++
                    navigationClicks++
                }
                unknown = unknown || outcome.unknown || (outcome.attempted && !outcome.confirmed)
                if (!outcome.confirmed || outcome.unknown) {
                    backStepFacts += buildJsonObject {
                        put("step", step)
                        put("attempted", outcome.attempted)
                        put("confirmed", outcome.confirmed)
                        put("unknown", outcome.unknown)
                        put("ime_before", beforeBack.keyboard != null)
                    }
                    return result(failure = "chrome_omnibox_back_unconfirmed")
                }
                pause(350L)
                if (!allowed()) return result(failure = "control_authority_revoked")
                val afterBack = settleChromeTransition(seekToolbar = false)
                if (!allowed()) return result(failure = "control_authority_revoked")
                diagnostics = chromeSelectorFacts(afterBack)
                toolbarSamples += diagnostics
                val contextRefusal = chromeBackContextRefusal(
                    beforeBack, afterBack,
                    requireToolbarRestored = (step == 2),
                    priorVerifiedBenchmarkText = priorVerifiedBenchmarkText,
                )
                backStepFacts += buildJsonObject {
                    put("step", step)
                    put("attempted", outcome.attempted)
                    put("confirmed", outcome.confirmed)
                    put("unknown", outcome.unknown)
                    put("ime_before", beforeBack.keyboard != null)
                    put("ime_after", afterBack.keyboard != null)
                    put("window_context_changed", beforeBack.windowContext != afterBack.windowContext)
                    put("toolbar_restored", chromeMenuButton(afterBack) != null)
                    contextRefusal?.let { put("context_refusal", it) }
                }
                if (contextRefusal != null) {
                    backContextRefusalDetail = contextRefusal
                    return result(failure = "chrome_omnibox_back_context_changed")
                }
                first = afterBack
                if (chromeMenuButton(first) != null) break
                priorBackFrame = beforeBack
            }
        }

        // Input mode always preserves every existing tab and requires a new one.
        val beforeNavigation = first
        val previousCount = chromeTabCount(beforeNavigation)
        tabsBefore = previousCount
        val menu = chromeMenuButton(first)
            ?: return result(failure = "unique_chrome_menu_unavailable")
        if (!runClick(first, menu, "chrome_menu_dispatch"))
            return result(failure = "chrome_menu_unconfirmed")
        if (!allowed()) return result(failure = "control_authority_revoked")
        val popup = capture()
        nativeRefusal = popup.reason
        if (!allowed()) return result(failure = "control_authority_revoked")
        val newTabCommand = chromeNewTabMenuItem(popup)
            ?: return result(failure = "verified_new_tab_menu_unavailable")
        if (!runClick(popup, newTabCommand, "chrome_new_tab_dispatch"))
            return result(failure = "chrome_new_tab_unconfirmed")

        // At most three passive probes and zero action replay.
        onPhase("new_blank_tab_preflight")
        var newTabFrame: ControlFrame? = null
        for (read in 0..2) {
            pause(if (read == 0) 350L else 250L)
            if (!allowed()) return result(failure = "control_authority_revoked")
            val probe = capture()
            nativeRefusal = probe.reason
            diagnostics = chromeSelectorFacts(probe)
            if (probe.reason != null && !readOnlyResampleReason(probe.reason))
                return result(failure = "new_tab_native_security_refusal")
            if (probe.truncated || probe.navigationOnly)
                return result(failure = "new_tab_incomplete_frame")
            if (probe.reason == null && !sameChromeWindow(beforeNavigation, probe))
                return result(failure = "new_tab_chrome_context_changed")
            if (verifiedBlankAfterNewTabCommand(beforeNavigation, probe, newTabCommandConfirmed = true)) {
                newTabFrame = probe
                break
            }
        }
        first = newTabFrame ?: return result(failure = "new_blank_chrome_tab_unverified")
        tabsAfter = chromeTabCount(first)
        // Count-less NTP is safe to edit but is not independent tab-count proof.
        newTabVerified = previousCount != null && tabsAfter == previousCount + 1
        newTabEvidence = if (newTabVerified)
            "tab_count_increment_and_blank_editor" else "confirmed_new_tab_command_and_new_ntp_surface"

        // Pre-focus non-editable NTP search box is allowed only in this proven
        // new tab; post-focus editor, Samsung IME and 0/0 caret remain mandatory.
        val target = blankOmnibox(first)
            ?: return result(failure = "unique_blank_chrome_omnibox_unavailable")
        if (first.inputTarget == null || first.keyboard?.packageName != SAMSUNG_IME) {
            if (!runClick(first, target, "chrome_editor_focus_dispatch", editorFocus = true))
                return result(failure = "chrome_editor_focus_unconfirmed")
            pause(350L)
            if (!allowed()) return result(failure = "control_authority_revoked")
            val focused = settleChromeTransition()
            if (!allowed()) return result(failure = "control_authority_revoked")
            diagnostics = chromeSelectorFacts(focused)
            val focusedCount = chromeTabCount(focused)
            if (!sameChromeWindow(first, focused) ||
                (tabsAfter != null && focusedCount != null && tabsAfter != focusedCount))
                return result(failure = "chrome_editor_context_changed_after_focus")
            first = focused
        }
        if (!diagnosticOnly && first.inputTarget?.let {
                it.resourceId == "$CHROME:id/url_bar" && it.sourceWindowId == first.windowId
            } != true) return result(failure = "fresh_chrome_tab_address_editor_unverified")
        onPhase("editor_preflight")
        return result(frame = first)
    }

    private suspend fun prepareChromeEditor(
        port: AndroidControlPort, control: GenericControl, session: ControlSession,
        allowed: () -> Boolean, diagnosticOnly: Boolean,
        priorVerifiedBenchmarkText: String? = null,
        onPhase: (String) -> Unit,
    ): ChromeReady = prepareChromeEditorWithSeam(
        capture = { port.capture() },
        inspectWindow = { chromeWindowFacts() },
        clickNode = { frame, target, phase ->
            val filter = target.resourceId.ifBlank {
                val labels = menuDescriptions + setOf("새 탭", "New tab")
                listOf(target.description.trim(), target.text.trim()).firstOrNull { it in labels }.orEmpty()
            }
            if (filter.isBlank()) {
                ChromeActionOutcome(confirmed = false, attempted = false, unknown = false)
            } else {
                val observed = control.observe(filter = filter)
                if (!observed.success ||
                    observed.data["output_truncated"]?.jsonPrimitive?.booleanOrNull != false ||
                    observed.data["package_name"]?.jsonPrimitive?.content != CHROME ||
                    observed.data["window_id"]?.jsonPrimitive?.intOrNull != frame.windowId ||
                    observed.data["configuration_revision"]?.jsonPrimitive?.longOrNull != frame.configurationRevision ||
                    observed.data["node_actions_allowed"]?.jsonPrimitive?.booleanOrNull != true) {
                    ChromeActionOutcome(confirmed = false, attempted = false, unknown = false)
                } else {
                    val matches = (observed.data["nodes"] as? JsonArray)
                        ?.mapNotNull { it as? JsonObject }
                        ?.filter {
                            it["source_window_id"]?.jsonPrimitive?.intOrNull == frame.windowId &&
                                it["resource_id"]?.jsonPrimitive?.content == target.resourceId &&
                                (target.resourceId.isNotEmpty() ||
                                    (it["description"]?.jsonPrimitive?.content == target.description.take(100) &&
                                        it["text"]?.jsonPrimitive?.content == target.text.take(100))) &&
                                it["class_name"]?.jsonPrimitive?.content == target.className &&
                                it["bounds"]?.jsonPrimitive?.content == target.bounds &&
                                it["clickable"]?.jsonPrimitive?.booleanOrNull == true &&
                                it["enabled"]?.jsonPrimitive?.booleanOrNull == true
                        }.orEmpty()
                    val observationId = observed.data["observation_id"]?.jsonPrimitive?.content
                    val nodeId = matches.singleOrNull()?.get("node_id")?.jsonPrimitive?.intOrNull
                    if (matches.size != 1 || observationId == null || nodeId == null) {
                        ChromeActionOutcome(confirmed = false, attempted = false, unknown = false)
                    } else {
                        onPhase(phase)
                        val effect = control.action(buildJsonObject {
                            put("action", "click")
                            put("session_id", session.id)
                            put("observation_id", observationId)
                            put("node_id", nodeId)
                        })
                        val attempted = effect.data["action_attempted"]?.jsonPrimitive?.booleanOrNull == true
                        val actionUnknown = effect.data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull == true
                        ChromeActionOutcome(
                            confirmed = effect.success && !actionUnknown,
                            attempted = attempted,
                            unknown = actionUnknown || (attempted && !effect.success),
                        )
                    }
                }
            }
        },
        dispatchBack = { frame, phase ->
            val observed = control.observe(filter = "$CHROME:id/url_bar")
            val observationId = observed.data["observation_id"]?.jsonPrimitive?.contentOrNull
            if (!observed.success || observationId == null ||
                !guardedBackObservationMatches(frame, observed.data, priorVerifiedBenchmarkText)) {
                ChromeActionOutcome(confirmed = false, attempted = false, unknown = false)
            } else {
                onPhase(phase)
                val effect = control.action(buildJsonObject {
                    put("action", "back")
                    put("session_id", session.id)
                    put("observation_id", observationId)
                })
                val attempted = effect.data["action_attempted"]?.jsonPrimitive?.booleanOrNull == true
                val actionUnknown = effect.data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull == true
                ChromeActionOutcome(
                    confirmed = effect.success && !actionUnknown,
                    attempted = attempted,
                    unknown = actionUnknown || (attempted && !effect.success),
                )
            }
        },
        allowed = allowed,
        diagnosticOnly = diagnosticOnly,
        onPhase = onPhase,
        pause = { delay(it) },
        priorVerifiedBenchmarkText = priorVerifiedBenchmarkText,
    )

    internal fun sameEditor(first: ControlFrame, later: ControlFrame): Boolean {
        if (first.reason != null || later.reason != null ||
            first.truncated || later.truncated ||
            first.navigationOnly || later.navigationOnly) return false
        val a = first.inputTarget ?: return false
        val b = later.inputTarget ?: return false
        if (!a.focused || !a.editable || !a.enabled || a.password ||
            !b.focused || !b.editable || !b.enabled || b.password) return false
        if (first.windowId != later.windowId || first.packageName != later.packageName ||
            a.className != b.className || a.sourcePackage != b.sourcePackage ||
            a.sourceWindowId != b.sourceWindowId) return false
        if (!a.nativeEditorId.isNullOrBlank() || !b.nativeEditorId.isNullOrBlank()) {
            return a.nativeEditorId == b.nativeEditorId
        }
        if (a.resourceId.isNotBlank() || b.resourceId.isNotBlank()) {
            if (a.resourceId != b.resourceId) return false
            fun sameResourceNode(node: ai.closepaw.chatgpt.control.ControlNode) =
                node.resourceId == a.resourceId && node.className == a.className &&
                    node.sourceWindowId == a.sourceWindowId && node.sourcePackage == a.sourcePackage
            if (first.nodes.count(::sameResourceNode) != 1 || later.nodes.count(::sameResourceNode) != 1) {
                return false
            }
            if (a.webEditorContainer != b.webEditorContainer || a.parent != b.parent) return false
            if (a.bounds.isNotBlank() && a.bounds == b.bounds) return true
            val ab = ai.closepaw.chatgpt.control.KeyboardImageFallbackPolicy.bounds(a.bounds) ?: return false
            val bb = ai.closepaw.chatgpt.control.KeyboardImageFallbackPolicy.bounds(b.bounds) ?: return false
            return ab.top == bb.top && ab.bottom == bb.bottom && (ab.left == bb.left || ab.right == bb.right) &&
                2L * (minOf(ab.right, bb.right) - maxOf(ab.left, bb.left)) >= maxOf(ab.right - ab.left, bb.right - bb.left)
        }
        if (a.webEditorContainer != b.webEditorContainer) return false
        if (a.webEditorContainer >= 0 && a.parent < 0) return false
        return a.bounds.isNotBlank() && a.bounds == b.bounds && a.parent == b.parent
    }

    /** Verifies the live ControlSession still has the exact immutable (id, epoch) owned by this QA run. */
    internal fun ownsActiveSession(
        session: ControlSession,
        ownedToken: ControlSession.ActiveToken?,
    ): Boolean = session.isCurrentActive(ownedToken)

    /**
     * Starts the session on [mainDispatcher] and records ownership + correlation reference
     * INSIDE the main-dispatcher block before returning so prompt cancellation during the
     * dispatcher handoff back to the caller can never orphan an unrecorded active session.
     */
    internal suspend fun startSessionAndRecord(
        mainDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        startAndSnapshot: () -> ControlSession.ActiveToken?,
        recordOwned: (ControlSession.ActiveToken) -> Unit,
    ): ControlSession.ActiveToken? = kotlinx.coroutines.withContext(mainDispatcher) {
        startAndSnapshot()?.also(recordOwned)
    }

    /**
     * Exception- and cancellation-safe nested cleanup so ControlScreenLease.mutex is always
     * released even if invalidation, indicator teardown, or conditional session end throws/cancels.
     * Cleanup failures are reported via redacted [onCleanupError] step callbacks, never silently dropped.
     */
    internal suspend fun cleanupSessionLease(
        invalidate: () -> Unit,
        stopActivity: suspend () -> Unit,
        endOwnedSession: suspend () -> Unit,
        unlockMutex: () -> Unit,
        onCleanupError: (String, Throwable) -> Unit = { _, _ -> },
    ) {
        try {
            try {
                runCatching { invalidate() }.onFailure { onCleanupError("invalidate", it) }
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching { stopActivity() }.onFailure { onCleanupError("stop_activity", it) }
                }
            }
        } finally {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching { endOwnedSession() }.onFailure { onCleanupError("end_session", it) }
                }
            } finally {
                unlockMutex()
            }
        }
    }

    internal val batchFileLock = Any()

    private fun reportFile(context: Context) = File(context.filesDir, "debug-qa/one-stop-result.json")
    private fun batchFile(context: Context) = File(context.filesDir, "debug-qa/one-stop-batch.json")
    internal fun lastBatchResult(context: Context): JsonObject? {
        if (!BuildConfig.DEBUG || context.packageName != CaptureQaAcceptance.CANDIDATE) return null
        return synchronized(batchFileLock) {
            val file = batchFile(context)
            if (!file.isFile || file.length() !in 1L..DebugQaLifecyclePolicy.MAX_BATCH_FILE_BYTES) return@synchronized null
            runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
                ?.takeIf { it["source_commit"]?.jsonPrimitive?.content == BuildConfig.CAPTURE_QA_SOURCE_SHA }
        }
    }
    internal fun batchResultById(context: Context, batchId: String): JsonObject? {
        if (!BuildConfig.DEBUG || context.packageName != CaptureQaAcceptance.CANDIDATE) return null
        if (!batchId.matches(Regex("[a-f0-9-]{36}"))) return null
        return synchronized(batchFileLock) {
            val historyFile = File(context.filesDir, "debug-qa/history/batch-$batchId.json")
            val fromHistory = if (historyFile.isFile && historyFile.length() in 1L..DebugQaLifecyclePolicy.MAX_BATCH_FILE_BYTES) {
                runCatching { Json.parseToJsonElement(historyFile.readText()).jsonObject }.getOrNull()
                    ?.takeIf {
                        it["source_commit"]?.jsonPrimitive?.content == BuildConfig.CAPTURE_QA_SOURCE_SHA &&
                            it["batch_id"]?.jsonPrimitive?.content == batchId
                    }
            } else null
            fromHistory ?: lastBatchResult(context)?.takeIf {
                it["batch_id"]?.jsonPrimitive?.content == batchId
            }
        }
    }
    internal fun savePreRunBatch(context: Context, snapshot: JsonObject, overwriteLatest: Boolean = true) {
        require(BuildConfig.DEBUG && context.packageName == CaptureQaAcceptance.CANDIDATE)
        synchronized(batchFileLock) {
            if (overwriteLatest) atomicWrite(batchFile(context), snapshot)
            val batchId = snapshot["batch_id"]?.jsonPrimitive?.content.orEmpty()
            if (batchId.matches(Regex("[a-f0-9-]{36}"))) {
                atomicWrite(File(context.filesDir, "debug-qa/history/batch-$batchId.json"), snapshot)
            }
        }
    }
    internal fun saveBatch(context: Context, id: String, state: String, reason: String,
                           results: List<JsonObject>, run: DebugQaApprovedRun) {
        require(BuildConfig.DEBUG && context.packageName == CaptureQaAcceptance.CANDIDATE)
        val snapshot = buildJsonObject {
            put("schema_version", 3); put("batch_id", id)
            put("source_commit", BuildConfig.CAPTURE_QA_SOURCE_SHA)
            put("version_name", BuildConfig.VERSION_NAME); put("version_code", BuildConfig.VERSION_CODE)
            put("state", state); put("reason", reason)
            put("request_id", run.request.id); put("mode", run.request.mode.code)
            put("projection_consent", run.consent.name)
            put("service_action", run.request.mode.action)
            put("service_admission", DebugQaAdmissionOutcome.ACCEPTED.name)
            put("candidate_control_session_state",
                AgentService.instance?.remoteControlSession?.state?.name ?: "unavailable")
            // Candidate in-process state cannot prove a separate Release package session ended.
            put("release_session_ended_externally_verified", false)
            put("pre_run_terminal", false)
            put("repetitions_requested", run.request.mode.repetitions)
            put("repetitions_completed", results.count { it["outcome"]?.jsonPrimitive?.content == "PASS" })
            put("runs", JsonArray(results))
            put("request_confirmed_at_epoch_ms", run.request.confirmedAtEpochMs)
            run.request.confirmedAtElapsedMs?.let { put("request_confirmed_at_elapsed_realtime_ms", it) }
            put("approval_completed_at_epoch_ms", run.approvedAtEpochMs)
            run.approvedAtElapsedMs?.let { put("approval_completed_at_elapsed_realtime_ms", it) }
            put("last_updated_epoch_ms", System.currentTimeMillis())
            put("last_updated_elapsed_realtime_ms", SystemClock.elapsedRealtime())
        }
        synchronized(batchFileLock) {
            atomicWrite(batchFile(context), snapshot)
            if (state != "IN_PROGRESS" && id.matches(Regex("[a-f0-9-]{36}"))) {
                atomicWrite(File(context.filesDir, "debug-qa/history/batch-$id.json"), snapshot)
            }
        }
    }
    /** Read-only persisted checkpoint, never an authorization or replay token. */
    fun lastResult(context: Context): JsonObject? {
        if (!BuildConfig.DEBUG || context.packageName != CaptureQaAcceptance.CANDIDATE) return null
        val file = reportFile(context)
        if (!file.isFile || file.length() !in 1L..DebugQaLifecyclePolicy.MAX_BATCH_FILE_BYTES) return null
        return runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
            ?.takeIf { item ->
                item["source_commit"]?.jsonPrimitive?.content == BuildConfig.CAPTURE_QA_SOURCE_SHA &&
                    item["suite"]?.jsonPrimitive?.content?.let {
                        it == "six_key_first_pass" || it == "structure_diagnostic"
                    } == true &&
                    item["fixture_package"]?.jsonPrimitive?.content == CHROME
            }
    }

    private fun write(context: Context, result: JsonObject) {
        atomicWrite(reportFile(context), result)
        if (result["outcome"]?.jsonPrimitive?.content != "IN_PROGRESS") {
            val id = result["run_id"]?.jsonPrimitive?.content.orEmpty()
            if (id.matches(Regex("[a-f0-9-]{36}"))) {
                atomicWrite(File(context.filesDir, "debug-qa/history/$id.json"), result)
            }
        }
    }

    private fun atomicWrite(file: File, result: JsonObject) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(result.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    /** Same device clock domains as Android logcat -v epoch. No app content. */
    internal fun deviceClockEvidence(epochMs: Long, elapsedRealtimeMs: Long) = buildJsonObject {
        put("epoch_ms", epochMs)
        put("elapsed_realtime_ms", elapsedRealtimeMs)
    }

    /** Single-device-clock lifecycle timeline; never fabricates external Release timestamps. */
    internal fun lifecycleTimelineEvidence(
        confirmedEpochMs: Long,
        confirmedElapsedMs: Long?,
        approvedEpochMs: Long?,
        approvedElapsedMs: Long?,
        runEntryEpochMs: Long,
        runEntryElapsedMs: Long,
        awaitChromeEpochMs: Long? = null,
        awaitChromeElapsedMs: Long? = null,
        sessionStartedEpochMs: Long? = null,
        sessionStartedElapsedMs: Long? = null,
        endedEpochMs: Long? = null,
        endedElapsedMs: Long? = null,
    ) = buildJsonObject {
        put("clock_domain", "android_epoch_ms_plus_elapsed_realtime_ms")
        put("request_confirmed", buildJsonObject {
            put("epoch_ms", confirmedEpochMs)
            confirmedElapsedMs?.let { put("elapsed_realtime_ms", it) }
        })
        if (approvedEpochMs != null) {
            put("approval_completed", buildJsonObject {
                put("epoch_ms", approvedEpochMs)
                approvedElapsedMs?.let { put("elapsed_realtime_ms", it) }
            })
        }
        put("run_entry", deviceClockEvidence(runEntryEpochMs, runEntryElapsedMs))
        if (awaitChromeEpochMs != null && awaitChromeElapsedMs != null) {
            put("await_chrome_completed", deviceClockEvidence(awaitChromeEpochMs, awaitChromeElapsedMs))
        }
        if (sessionStartedEpochMs != null && sessionStartedElapsedMs != null) {
            put("session_started", deviceClockEvidence(sessionStartedEpochMs, sessionStartedElapsedMs))
        }
        if (endedEpochMs != null && endedElapsedMs != null) {
            put("ended", deviceClockEvidence(endedEpochMs, endedElapsedMs))
        }
    }

    internal data class TerminalFailureRecord(
        val failureCount: Int,
        val firstFailureCode: String?,
        val shouldRecordStep: Boolean,
    )

    internal fun safeReasonCode(code: String): String =
        code.takeIf { it.length in 1..80 && it.matches(Regex("[a-z0-9_]+")) }
            ?: "unavailable"

    /** Ensures terminal BLOCKED/UNKNOWN reports never emit failure_count=0 or empty step_events. */
    internal fun ensureTerminalFailureRecorded(
        currentFailureCount: Int,
        currentFirstFailureCode: String?,
        currentStepEventCount: Int,
        reason: String,
    ): TerminalFailureRecord {
        val code = currentFirstFailureCode ?: safeReasonCode(reason)
        val missingCount = currentFailureCount <= 0
        val missingSteps = currentStepEventCount <= 0
        return TerminalFailureRecord(
            failureCount = if (missingCount) 1 else currentFailureCount,
            firstFailureCode = code,
            shouldRecordStep = missingCount || missingSteps,
        )
    }

    suspend fun run(context: Context, status: (String) -> Unit, request: DebugQaRunRequest,
                    batchId: String, approvedProjection: ProjectionImageLease,
                    approvedRun: DebugQaApprovedRun? = null,
                    priorVerifiedBenchmarkText: String? = null): JsonObject {
        val diagnosticOnly = request.mode.diagnosticOnly
        require(BuildConfig.DEBUG && context.packageName == CaptureQaAcceptance.CANDIDATE)
        val settings = McpExecutionSettings(context)
        val ctx = RunContext(context, status, request, batchId, diagnosticOnly, approvedRun)

        if (CaptureQaAcceptance.setup(context) != "ready") return ctx.stop("debug_qa_setup_required")
        if (!settings.enabled) return ctx.stop("debug_connection_not_enabled")
        val service = AgentService.instance ?: return ctx.stop("accessibility_permission_missing")
        val session = service.remoteControlSession
        ctx.sessionReference = session.id
        if (session.state !in setOf(ControlSession.State.IDLE, ControlSession.State.ENDED))
            return ctx.stop("another_control_session_active")
        if (ProjectionImageTransport.active() !== approvedProjection || !approvedProjection.screenObservationAllowed())
            return ctx.stop("android_observation_approval_required")

        // Chrome was opened by the physically visible QA Activity immediately
        // after starting the foreground runner. A background FGS must NOT try to
        // bypass Android background-activity-launch restrictions.
        ctx.stage = "await_chrome"
        ctx.checkpoint("waiting_for_visible_activity_chrome_launch")
        val deadline = SystemClock.elapsedRealtime() + 60_000L
        var foreground = false
        while (SystemClock.elapsedRealtime() < deadline) {
            currentCoroutineContext().ensureActive()
            if (AgentService.instance !== service || !settings.genericControlEnabled ||
                ProjectionImageTransport.active() !== approvedProjection ||
                !approvedProjection.screenObservationAllowed())
                return ctx.stop("authority_changed_during_preflight")
            if (session.state !in setOf(ControlSession.State.IDLE, ControlSession.State.ENDED))
                return ctx.stop("session_changed_during_preflight")
            foreground = withContext(Dispatchers.Main) {
                val root = service.rootInActiveWindow ?: return@withContext false
                try { root.packageName?.toString() == CHROME } finally { root.recycle() }
            }
            if (foreground) break
            delay(250)
        }
        if (!foreground) return ctx.stop("blank_chrome_deadline")
        ctx.awaitChromeCompletedEpochMs = System.currentTimeMillis()
        ctx.awaitChromeCompletedElapsedMs = SystemClock.elapsedRealtime()
        if (!ControlScreenLease.mutex.tryLock()) return ctx.stop("ui_busy")
        val ownedTokenRef = java.util.concurrent.atomic.AtomicReference<ControlSession.ActiveToken?>(null)
        var port: AndroidControlPort? = null
        var control: GenericControl? = null
        return try {
            val allowed: () -> Boolean = {
                AgentService.instance === service && settings.enabled && settings.genericControlEnabled &&
                    ProjectionImageTransport.active() === approvedProjection &&
                    approvedProjection.screenObservationAllowed() &&
                    session.isCurrentActive(ownedTokenRef.get())
            }
            val localPort = AndroidControlPort(context, runtime = settings.runtimeControl, allowed = allowed)
            val localControl = GenericControl(localPort, allowed)
            port = localPort
            control = localControl
            val startedToken = startSessionAndRecord(
                mainDispatcher = Dispatchers.Main,
                startAndSnapshot = { session.startAndSnapshot() },
                recordOwned = { token ->
                    ownedTokenRef.set(token)
                    ctx.sessionReference = token.id
                },
            ) ?: return ctx.stop("explicit_session_start_refused")
            ctx.sessionStartedEpochMs = System.currentTimeMillis()
            ctx.sessionStartedElapsedMs = SystemClock.elapsedRealtime()
            localPort.activity(true)
            // Chrome's accessibility window can be replaced during the first resume
            // animation. Let the foreground settle before a fresh full security scan.
            delay(350L)
            if (!allowed()) return ctx.stop("control_authority_revoked")
            ctx.stage = "editor_preflight"
            ctx.checkpoint("native_preflight")
            val prepared = prepareChromeEditor(
                localPort, localControl, session, allowed, diagnosticOnly,
                priorVerifiedBenchmarkText = priorVerifiedBenchmarkText,
            ) { phase ->
                ctx.stage = phase
                ctx.checkpoint("guarded_chrome_preparation")
            }
            ctx.recordPrepared(prepared)
            val first = prepared.frame
                ?: return continueAfterFailure(ctx, localPort, localControl, session, service, approvedProjection, allowed,
                    prepared.failure ?: "chrome_preparation_refused")
            // Independent reason codes and booleans are written before any key dispatch.
            val diagnosed = editorPreflightFacts(first)
            ctx.preflightFacts = diagnosed
            editorPreflightReason(diagnosed)?.let {
                return continueAfterFailure(ctx, localPort, localControl, session, service, approvedProjection, allowed, it)
            }
            val observedLayout = NativeKeyboardStructuralCertification.classifyPhysicalLayout(first).code
            ctx.initialPhysicalLayout = observedLayout
            ctx.finalPhysicalLayout = observedLayout

            // At most three fresh read-only observations in the same locally
            // approved session, only for the exact capture-generation refusal.
            // No retry of a touch, no reuse of stale images or relaxed guard.
            if (diagnosticOnly) {
                return runStructureDiagnostic(ctx, localPort, localControl, session, first, allowed)
            }

            val execution = DebugSixKeyExecution.run(
                localPort, localControl, session, first, allowed,
                ownedToken = startedToken,
            ) { progress, reason ->
                ctx.verifiedKeys = progress.verified
                ctx.attempts = progress.attempts
                ctx.uncertain = progress.uncertain
                ctx.stage = progress.stage
                ctx.checkpoint(reason)
            }
            ctx.verifiedKeys = execution.progress.verified
            ctx.attempts = execution.progress.attempts
            ctx.uncertain = execution.progress.uncertain
            ctx.stage = execution.progress.stage
            ctx.keyEvidence.addAll(execution.evidence)
            ctx.diagnosticDurationMs += execution.observationElapsedMs
            ctx.diagnosticReadCount += execution.followUpNativeReads
            ctx.afterFailureStatus = execution.followUpStatus
            ctx.afterFailureEvidence = execution.followUpEvidence
            ctx.initialPhysicalLayout = execution.initialPhysicalLayout
            ctx.finalPhysicalLayout = execution.finalPhysicalLayout
            ctx.layoutSwitchAttempts = execution.layoutSwitchAttempts
            ctx.layoutSwitchConfirmed = execution.layoutSwitchConfirmed
            ctx.recoveryAttempts += execution.recoveryAttempts
            if (execution.failure != null) {
                ctx.stop(execution.failure)
            } else {
                // Linearization point: commit PASS only while this run's exact (id, epoch) is ACTIVE.
                val committedPass = session.commitIfCurrentActive(startedToken) {
                    if (allowed()) ctx.result("PASS", "six_physical_keys_editor_and_caret_confirmed")
                    else null
                }
                if (committedPass != null) {
                    committedPass.also { write(context, it) }
                } else {
                    ctx.uncertain = ctx.uncertain || ctx.attempts > 0
                    ctx.stop("control_authority_revoked")
                }
            }
        } catch (cancel: CancellationException) {
            ownedTokenRef.get()?.let { ctx.sessionReference = it.id }
            ctx.uncertain = ctx.uncertain || ctx.stage.endsWith("_dispatch")
            ctx.stop("run_cancelled")
        } catch (_: Exception) {
            ownedTokenRef.get()?.let { ctx.sessionReference = it.id }
            ctx.uncertain = ctx.uncertain || ctx.stage.endsWith("_dispatch")
            ctx.stop("guarded_qa_failed")
        } finally {
            val tokenToEnd = ownedTokenRef.get()
            cleanupSessionLease(
                invalidate = { control?.invalidate() },
                stopActivity = {
                    if (session.isCurrentActive(tokenToEnd)) port?.activity(false)
                },
                endOwnedSession = {
                    if (tokenToEnd != null) {
                        withContext(NonCancellable + Dispatchers.Main) {
                            session.endIfCurrentActive(tokenToEnd)
                        }
                    }
                },
                unlockMutex = { ControlScreenLease.mutex.unlock() },
                onCleanupError = { step, err ->
                    android.util.Log.w("ClosePawDebugQA", "cleanup_step_failed step=$step error=${err.javaClass.simpleName}")
                },
            )
        }
    }

    // Ordinary Chrome-setup failure does not terminate a live, user-approved
    // diagnostic session before independent, bounded READ-ONLY probes.
    private suspend fun continueAfterFailure(
        ctx: RunContext,
        port: AndroidControlPort,
        control: GenericControl,
        session: ControlSession,
        service: AgentService,
        approvedProjection: ProjectionImageLease,
        allowed: () -> Boolean,
        reason: String,
    ): JsonObject {
        ctx.firstFailureCode = safeReasonCode(reason)
        ctx.failureCount++
        ctx.recordStep("chrome_preparation", "FAILED", reason,
            ctx.chromeSelectorDiagnostics?.redactedJson() ?: buildJsonObject {})
        val liveChrome = allowed() && chromeWindowFacts().chromeRoot
        if (!DebugContinuousReadOnly.mayInspect(
                reason, ctx.diagnosticOnly, ctx.uncertain, allowed(), liveChrome)) {
            ctx.recordStep("independent_diagnostics", "DEPENDENCY_BLOCKED",
                "unsafe_or_ineligible")
            return ctx.stop(reason)
        }
        ctx.qaState = "STEP_FAILED_CONTINUING"
        ctx.stage = "post_failure_read_only"
        ctx.checkpoint("first_failure_preserved")
        DebugPostFailureProbes.collect(
            port = port, control = control, session = session,
            projectionLive = { approvedProjection.screenObservationAllowed() },
            accessibilityLive = { AgentService.instance === service },
            allowed = allowed, inspectWindow = { chromeWindowFacts() },
        ) { finding ->
            ctx.postFailureStepCount++
            ctx.recordStep(finding.stepId, finding.status, finding.reason, finding.details)
            ctx.stage = finding.stepId
            ctx.qaState = if (finding.status == "PERMISSION_REVOKED")
                "PERMISSION_REVOKED" else "STEP_FAILED_CONTINUING"
            ctx.checkpoint("independent_read_only_step")
        }
        if (ctx.qaState != "PERMISSION_REVOKED") {
            ctx.qaState = "DIAGNOSTICS_COMPLETE"
            ctx.stage = "post_failure_complete"
            ctx.checkpoint("independent_read_only_complete")
        }
        return ctx.stop(reason)
    }

    private suspend fun runStructureDiagnostic(
        ctx: RunContext,
        port: AndroidControlPort,
        control: GenericControl,
        session: ControlSession,
        first: ControlFrame,
        allowed: () -> Boolean,
    ): JsonObject {
        ctx.stage = "structure_diagnostic"
        ctx.checkpoint("one_no_key_observation")
        val originalEpoch = session.epoch
        for (index in 0 until DebugKeyboardEvidence.MAX_STRUCTURE_OBSERVATIONS) {
            currentCoroutineContext().ensureActive()
            if (!allowed() || session.epoch != originalEpoch) return ctx.stop("control_authority_revoked")
            if (index > 0) {
                ctx.recoveryAttempts++
                control.invalidate()
                delay(350L)
                if (!allowed() || session.epoch != originalEpoch) return ctx.stop("control_authority_revoked")
                val fresh = port.capture()
                ctx.diagnosticReadCount++
                if (!allowed() || session.epoch != originalEpoch) return ctx.stop("control_authority_revoked")
                val facts = editorPreflightFacts(fresh)
                if (editorPreflightReason(facts) != null || !sameEditor(first, fresh) ||
                    DisplayObservationRecoveryPolicy.recertificationRefusal(first, fresh) != null)
                    return ctx.stop("structure_retry_target_changed")
            }
            port.resetDebugCaptureEvidence()
            val diagnosticStart = SystemClock.elapsedRealtime()
            val observed = control.observe(includeImage = true, imageScope = "keyboard",
                keyboardSequenceLabels = listOf("ㄱ", "ㅏ", "ㄴ", "Space"))
            ctx.diagnosticDurationMs += SystemClock.elapsedRealtime() - diagnosticStart
            val reason = DebugKeyboardEvidence.nativeRefusalReason(observed.data)
            ctx.keyEvidence += DebugKeyboardEvidence.fromObservation(
                observed.data, observed.success, listOf("ㄱ", "ㅏ", "ㄴ", "Space"),
                if (observed.success) null else port.debugDisplayPlanRefusal(),
                if (observed.success) null else port.debugDisplayWindowCensus(),
                if (observed.success) null else port.debugCaptureWindowChangeEvidence(),
                cursorProofMismatch = if (observed.success) null else port.visualKeyboardContextMismatch())
            if (observed.success) {
                ctx.stage = "structure_diagnostic_saved"
                ctx.qaState = "DIAGNOSTICS_COMPLETE"
                ctx.recordStep("structure_diagnostic", "PASS", "structural_key_evidence_captured_without_input")
                return ctx.result("DIAGNOSTIC_COMPLETE", "structural_key_evidence_captured_without_input")
                    .also { write(ctx.context, it); ctx.publishLive() }
            }
            if (ctx.firstFailureCode == null) ctx.firstFailureCode = reason
            ctx.failureCount++
            ctx.recordStep("structure_diagnostic", "FAILED", reason)
            ctx.checkpoint("structure_refusal_preserved")
            if (!DebugKeyboardEvidence.mayRetryStructure(reason, index + 1,
                    allowed() && session.epoch == originalEpoch,
                    ctx.uncertain || observed.data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull == true))
                return ctx.stop("structure_observation_refused")
        }
        return ctx.stop("structure_observation_refused")
    }

    /** Holds per-run mutable evidence outside suspend run() to keep JVM method bytecode well below 64 KiB. */
    private class RunContext(
        val context: Context,
        val status: (String) -> Unit,
        val request: DebugQaRunRequest,
        val batchId: String,
        val diagnosticOnly: Boolean,
        val approvedRun: DebugQaApprovedRun? = null,
    ) {
        val runId: String = UUID.randomUUID().toString()
        val stepEvents = mutableListOf<JsonObject>()
        var qaState = "SESSION_ACTIVE"
        var sessionReference = "not_started"
        val eventStartElapsedMs = SystemClock.elapsedRealtime()
        var firstFailureCode: String? = null
        var failureCount = 0
        var postFailureStepCount = 0
        var verifiedKeys = 0
        var attempts = 0
        var setupClickAttempts = 0
        var uncertain = false
        var stage = "preflight"
        var preflightFacts: EditorPreflightFacts? = null
        var nativePreflightRefusal: String? = null
        var chromeSelectorDiagnostics: ChromeSelectorFacts? = null
        var chromeToolbarSamples = emptyList<ChromeSelectorFacts>()
        var priorChromeTabCount: Int? = null
        var newChromeTabCount: Int? = null
        var newChromeTabVerified = false
        var navigationClickAttempts = 0
        var chromeOmniboxBackAttempts = 0
        var chromeBackContextRefusalDetail: String? = null
        var chromeBackStepFacts: List<JsonObject> = emptyList()
        var chromeReadCount = 0
        var chromeRecoveryReads = 0
        var firstChromeRefusal: String? = null
        var foregroundFactsAtRefusal: ChromeWindowFacts? = null
        val keyEvidence = mutableListOf<JsonObject>()
        var afterFailureEvidence: JsonObject? = null
        var afterFailureStatus = "not_required"
        var diagnosticDurationMs = 0L
        var diagnosticReadCount = 0
        var initialPhysicalLayout: String? = null
        var finalPhysicalLayout: String? = null
        var layoutSwitchAttempts = 0
        var layoutSwitchConfirmed = false
        var recoveryAttempts = 0
        val started = SystemClock.elapsedRealtime()
        val startedEpochMs = System.currentTimeMillis()
        var awaitChromeCompletedEpochMs: Long? = null
        var awaitChromeCompletedElapsedMs: Long? = null
        var sessionStartedEpochMs: Long? = null
        var sessionStartedElapsedMs: Long? = null
        var newTabEvidence = "none"

        fun recordPrepared(prepared: ChromeReady) {
            nativePreflightRefusal = prepared.nativeRefusal
            chromeReadCount = prepared.chromeReadCount
            chromeRecoveryReads = prepared.chromeRecoveryReads
            firstChromeRefusal = prepared.firstChromeRefusal
            foregroundFactsAtRefusal = prepared.foregroundFactsAtRefusal
            chromeSelectorDiagnostics = prepared.diagnostics
            chromeToolbarSamples = prepared.toolbarSamples
            priorChromeTabCount = prepared.tabsBefore
            newChromeTabCount = prepared.tabsAfter
            newChromeTabVerified = prepared.newTabVerified
            newTabEvidence = prepared.newTabEvidence
            navigationClickAttempts = prepared.navigationClicks
            chromeOmniboxBackAttempts = prepared.omniboxBackAttempts
            chromeBackContextRefusalDetail = prepared.backContextRefusalDetail
            chromeBackStepFacts = prepared.backStepFacts
            setupClickAttempts = prepared.focusClicks
            uncertain = prepared.unknown
        }

        fun recordStep(stepId: String, statusCode: String, reasonCode: String,
                       details: JsonObject = buildJsonObject {}) {
            if (stepEvents.size >= 20) return
            val sequence = stepEvents.size + 1
            val eventEpochMs = System.currentTimeMillis()
            val eventElapsedMs = SystemClock.elapsedRealtime()
            stepEvents += buildJsonObject {
                put("sequence", sequence)
                put("run_id", runId)
                put("device_clock", deviceClockEvidence(eventEpochMs, eventElapsedMs))
                put("step_id", safeReasonCode(stepId))
                put("status", safeReasonCode(statusCode.lowercase()).uppercase())
                put("reason_code", safeReasonCode(reasonCode))
                put("elapsed_ms", SystemClock.elapsedRealtime() - eventStartElapsedMs)
                put("facts", details)
            }
            // ADB logcat correlation marker. Never log frame content, URLs,
            // keyboard labels, pixels, coordinates or any arbitrary error string.
            android.util.Log.i("ClosePawQaClock",
                "run_id=$runId seq=$sequence epoch_ms=$eventEpochMs elapsed_realtime_ms=$eventElapsedMs")
        }

        fun result(outcome: String, reason: String) = buildJsonObject {
            put("schema_version", 3)
            put("run_id", runId)
            put("batch_id", batchId)
            put("request_id", request.id)
            put("session_id", sessionReference)
            put("qa_state", qaState)
            put("failure_count", failureCount)
            firstFailureCode?.let { put("first_failure_code", it) }
            put("step_events", JsonArray(stepEvents))
            put("post_failure_step_count", postFailureStepCount)
            put("cause_confidence", "UNVERIFIED")
            put("source_commit", BuildConfig.CAPTURE_QA_SOURCE_SHA)
            put("version_name", BuildConfig.VERSION_NAME)
            put("version_code", BuildConfig.VERSION_CODE)
            put("started_at_epoch_ms", startedEpochMs)
            put("started_at_elapsed_realtime_ms", started)
            put("device_clock_contract", "android_epoch_ms_plus_elapsed_realtime_ms")
            val endEpoch = if (outcome != "IN_PROGRESS") System.currentTimeMillis() else null
            val endElapsed = if (outcome != "IN_PROGRESS") SystemClock.elapsedRealtime() else null
            if (endEpoch != null && endElapsed != null) {
                put("ended_at_epoch_ms", endEpoch)
                put("ended_at_elapsed_realtime_ms", endElapsed)
            }
            put("device_clock_timeline", lifecycleTimelineEvidence(
                confirmedEpochMs = request.confirmedAtEpochMs,
                confirmedElapsedMs = request.confirmedAtElapsedMs,
                approvedEpochMs = approvedRun?.approvedAtEpochMs,
                approvedElapsedMs = approvedRun?.approvedAtElapsedMs,
                runEntryEpochMs = startedEpochMs,
                runEntryElapsedMs = started,
                awaitChromeEpochMs = awaitChromeCompletedEpochMs,
                awaitChromeElapsedMs = awaitChromeCompletedElapsedMs,
                sessionStartedEpochMs = sessionStartedEpochMs,
                sessionStartedElapsedMs = sessionStartedElapsedMs,
                endedEpochMs = endEpoch,
                endedElapsedMs = endElapsed,
            ))
            put("fixture_package", CHROME)
            put("input_method_package", SAMSUNG_IME)
            put("suite", if (diagnosticOnly) "structure_diagnostic" else "six_key_first_pass")
            put("diagnostic_only", diagnosticOnly)
            put("stage", stage)
            put("last_safe_checkpoint", if (verifiedKeys == 0) "before_first_key" else
                "key_" + verifiedKeys + "_confirmed")
            put("outcome", outcome)
            put("reason", reason)
            put("completed_keys", verifiedKeys)
            put("touch_attempts", attempts)
            put("layout_switch_attempts", layoutSwitchAttempts)
            put("layout_switch_confirmed", layoutSwitchConfirmed)
            initialPhysicalLayout?.let { put("initial_physical_layout", safeReasonCode(it)) }
            finalPhysicalLayout?.let { put("final_physical_layout", safeReasonCode(it)) }
            put("editor_focus_click_attempts", setupClickAttempts)
            put("navigation_click_attempts", navigationClickAttempts)
            put("chrome_omnibox_back_attempts", chromeOmniboxBackAttempts)
            chromeBackContextRefusalDetail?.let { put("chrome_omnibox_back_context_refusal", safeReasonCode(it)) }
            if (chromeBackStepFacts.isNotEmpty())
                put("chrome_omnibox_back_events", JsonArray(chromeBackStepFacts))
            put("new_chrome_tab_verified", newChromeTabVerified)
            put("new_chrome_tab_evidence", newTabEvidence)
            priorChromeTabCount?.let { put("chrome_tabs_before", it) }
            newChromeTabCount?.let { put("chrome_tabs_after", it) }
            chromeSelectorDiagnostics?.let { put("chrome_selector_diagnostics", it.redactedJson()) }
            if (chromeToolbarSamples.isNotEmpty())
                put("chrome_toolbar_samples", JsonArray(chromeToolbarSamples.map { it.redactedJson() }))
            put("outcome_unknown", uncertain)
            put("projection_active", ProjectionImageTransport.active()?.screenObservationAllowed() == true)
            put("control_session_state", AgentService.instance?.remoteControlSession?.state?.name ?: "unavailable")
            put("recovery_attempts", recoveryAttempts)
            put("diagnostic_observations", keyEvidence.size)
            put("diagnostic_extra_native_reads", diagnosticReadCount)
            put("diagnostic_elapsed_ms", diagnosticDurationMs)
            put("post_failure_diagnostic_status", afterFailureStatus)
            afterFailureEvidence?.let { put("post_failure_read_only", it) }
            put("key_evidence", JsonArray(keyEvidence))
            put("chrome_preflight_read_count", chromeReadCount)
            put("chrome_preflight_read_only_recoveries", chromeRecoveryReads)
            firstChromeRefusal?.takeIf {
                it.length in 1..80 && it.matches(Regex("[a-z0-9_]+"))
            }?.let { put("first_chrome_capture_refusal", it) }
            foregroundFactsAtRefusal?.let { put("chrome_window_facts_at_refusal", it.redactedJson()) }
            put("actual_keys_dispatched", attempts)
            // Only proven benchmark prefixes, never arbitrary Chrome/user contents.
            put("confirmed_text", SixKey.expected(verifiedKeys))
            put("confirmed_caret", SixKey.expected(verifiedKeys).length)
            if (outcome == "PASS") {
                put("final_text", SixKey.expected(6))
                put("final_caret_start", 4); put("final_caret_end", 4)
            }
            put("goal_verified", outcome == "PASS" && verifiedKeys == SixKey.labels.size)
            put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            put("repetitions_completed", if (outcome == "PASS") 1 else 0)
            preflightFacts?.let { put("editor_preflight_checks", it.redactedJson()) }
            // A native fixed reason code only: never store Chrome content, package text or pixels.
            nativePreflightRefusal?.takeIf {
                it.length in 1..80 && it.matches(Regex("[a-z0-9_]+"))
            }?.let { put("native_preflight_refusal", it) }
        }

        fun publishLive() {
            atomicWrite(File(context.filesDir, "debug-qa/live-state.json"), buildJsonObject {
                put("schema_version", 1)
                put("run_id", runId)
                put("state", qaState)
                put("stage", safeReasonCode(stage))
                put("error_count", failureCount)
                put("error_code", firstFailureCode ?: "none")
                put("updated_at_epoch_ms", System.currentTimeMillis())
            })
            status(qaState + ": " + safeReasonCode(stage) + " errors=" + failureCount)
        }

        fun checkpoint(why: String) {
            write(context, result("IN_PROGRESS", why))
            publishLive()
        }

        fun stop(why: String): JsonObject {
            if (qaState != "PERMISSION_REVOKED") qaState = "SESSION_STOPPED"
            val terminal = ensureTerminalFailureRecorded(failureCount, firstFailureCode, stepEvents.size, why)
            firstFailureCode = terminal.firstFailureCode
            failureCount = terminal.failureCount
            if (terminal.shouldRecordStep) {
                recordStep(stage, if (uncertain) "UNKNOWN" else "FAILED", why)
            }
            return result(if (uncertain) "UNKNOWN" else "BLOCKED", why)
                .also { write(context, it); publishLive() }
        }
    }
}
