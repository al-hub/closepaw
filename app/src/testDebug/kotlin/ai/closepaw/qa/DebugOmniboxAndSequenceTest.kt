package ai.closepaw.qa

import ai.closepaw.chatgpt.control.ControlFrame
import ai.closepaw.chatgpt.control.ControlKeyboard
import ai.closepaw.chatgpt.control.ControlNode
import ai.closepaw.chatgpt.control.TapBounds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * #147 regression & safety-invariant tests for:
 * - P0 A: conditional guarded Back normalization (max 2 distinct states) for proven empty focused Chrome omnibox
 * - P0 B: DebugSixKeyExecution production visual_key_sequence + strict fail-closed on-device pixel-role gate
 * - P1: terminal BLOCKED/UNKNOWN failure_count >= 1 and step_events consistency
 */
class DebugOmniboxAndSequenceTest {
    private val chrome = "com.android.chrome"
    private val ime = "com.samsung.android.honeyboard"
    private val keyboard = ControlKeyboard(13301, ime, "$ime/.service.HoneyBoardService")

    private fun node(
        id: Int,
        role: String = "",
        window: Int = 13300,
        pkg: String = chrome,
        clickable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = true,
        password: Boolean = false,
        focused: Boolean = false,
        text: String = "",
        description: String = "",
        parent: Int = -1,
        selectionStart: Int = -1,
        selectionEnd: Int = -1,
        keyboardKey: String = "",
        visualKeyCandidate: Boolean = false,
        keyboardImageCandidate: Boolean = false,
    ) = ControlNode(
        id = id,
        resourceId = if (role.isEmpty()) "" else "$pkg:id/$role",
        sourceWindowId = window,
        sourcePackage = pkg,
        bounds = "10 10 110 110",
        clickable = clickable,
        editable = editable,
        enabled = enabled,
        password = password,
        focused = focused,
        empty = text.isEmpty(),
        text = text,
        description = description,
        parent = parent,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
        keyboardKey = keyboardKey,
        visualKeyCandidate = visualKeyCandidate,
        keyboardImageCandidate = keyboardImageCandidate,
    )

    private val blankFocusedUrl = node(
        1, "url_bar", clickable = true, editable = true, focused = true, selectionStart = 0, selectionEnd = 0,
    )
    private val suggestions = node(20, "omnibox_suggestions_dropdown")
    private val ntp = node(6, "search_box_text", clickable = true)
    private val menu = node(2, "menu_button", clickable = true)
    private fun counter(count: Int) = node(3, "tab_switcher_button", clickable = true, description = "$count tabs")
    private val newTabItem = node(4, "new_tab_menu_id", clickable = true)
    private val newTabLabel = node(5, "menu_item_text", parent = 4, text = "새 탭")

    private fun frame(
        vararg nodes: ControlNode,
        editor: ControlNode? = null,
        withIme: Boolean = false,
        windowId: Int = 13300,
        foregroundWindowId: Int = windowId,
        configRev: Long = 139L,
        windowContext: String = "ctx-13300",
        pkg: String = chrome,
        reason: String? = null,
        truncated: Boolean = false,
        navigationOnly: Boolean = false,
    ) = ControlFrame(
        packageName = pkg,
        windowId = windowId,
        foregroundWindowId = foregroundWindowId,
        configurationRevision = configRev,
        windowContext = windowContext,
        fingerprint = "fp-$windowId",
        nodes = nodes.toList(),
        reason = reason,
        truncated = truncated,
        navigationOnly = navigationOnly,
        inputTarget = editor,
        keyboard = if (withIme) keyboard else null,
    )

    private fun chromeFacts() = DebugOneStopQa.ChromeWindowFacts(
        true, true, true, true, true, true, true, false,
    )

    @Test fun p0a_focusedBlankOmniboxWithImeAndSuggestionsRecoversAfterTwoDistinctBacks() = runBlocking {
        val initialFocused = frame(blankFocusedUrl, suggestions, editor = blankFocusedUrl, withIme = true)
        val afterBack1ImeHidden = frame(blankFocusedUrl, suggestions, editor = blankFocusedUrl, withIme = false)
        val afterBack2ToolbarRestored = frame(ntp, menu, counter(10), editor = null, withIme = false)
        val popup = frame(newTabItem, newTabLabel)
        val newTabNtp = frame(ntp, counter(11), editor = null, withIme = false)
        val readyFocused = frame(blankFocusedUrl, counter(11), editor = blankFocusedUrl, withIme = true)

        val frames = ArrayDeque(listOf(
            initialFocused, initialFocused, initialFocused,
            afterBack1ImeHidden,
            afterBack2ToolbarRestored,
            popup,
            newTabNtp,
            readyFocused,
        ))
        var backs = 0
        val clicks = mutableListOf<Int>()
        val ready = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = { frames.removeFirst() },
            inspectWindow = { chromeFacts() },
            clickNode = { _, target, _ ->
                clicks += target.id
                DebugOneStopQa.ChromeActionOutcome(true, true, false)
            },
            dispatchBack = { _, _ ->
                backs++
                DebugOneStopQa.ChromeActionOutcome(true, true, false)
            },
            allowed = { true },
            diagnosticOnly = false,
            onPhase = {},
            pause = {},
        )

        assertNull(ready.failure)
        assertNotNull(ready.frame)
        assertEquals(2, backs)
        assertEquals(2, ready.omniboxBackAttempts)
        assertEquals(listOf(2, 4, 6), clicks)
        assertTrue(ready.newTabVerified)
        assertEquals(10, ready.tabsBefore)
        assertEquals(11, ready.tabsAfter)
        assertNull(DebugOneStopQa.editorPreflightReason(DebugOneStopQa.editorPreflightFacts(ready.frame!!)))
    }

    @Test fun p0a_toolbarRestoredAfterFirstBackNeverSendsSecondBack() = runBlocking {
        val initialFocused = frame(blankFocusedUrl, suggestions, editor = blankFocusedUrl, withIme = true)
        val afterBack1Restored = frame(ntp, menu, counter(10), editor = null, withIme = false)
        val popup = frame(newTabItem, newTabLabel)
        val newTabNtp = frame(ntp, counter(11), editor = null, withIme = false)
        val readyFocused = frame(blankFocusedUrl, counter(11), editor = blankFocusedUrl, withIme = true)

        val frames = ArrayDeque(listOf(
            initialFocused, initialFocused, initialFocused,
            afterBack1Restored,
            popup,
            newTabNtp,
            readyFocused,
        ))
        var backs = 0
        val ready = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = { frames.removeFirst() },
            inspectWindow = { chromeFacts() },
            clickNode = { _, _, _ -> DebugOneStopQa.ChromeActionOutcome(true, true, false) },
            dispatchBack = { _, _ -> backs++; DebugOneStopQa.ChromeActionOutcome(true, true, false) },
            allowed = { true },
            diagnosticOnly = false,
            onPhase = {},
            pause = {},
        )

        assertNull(ready.failure)
        assertEquals(1, backs)
        assertEquals(1, ready.omniboxBackAttempts)
        assertTrue(ready.newTabVerified)
    }

    @Test fun p0a_normalToolbarDispatchesZeroBacks() = runBlocking {
        val normal = frame(ntp, menu, counter(10), editor = null, withIme = false)
        val popup = frame(newTabItem, newTabLabel)
        val newTabNtp = frame(ntp, counter(11), editor = null, withIme = false)
        val readyFocused = frame(blankFocusedUrl, counter(11), editor = blankFocusedUrl, withIme = true)

        val frames = ArrayDeque(listOf(normal, popup, newTabNtp, readyFocused))
        var backs = 0
        val ready = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = { frames.removeFirst() },
            inspectWindow = { chromeFacts() },
            clickNode = { _, _, _ -> DebugOneStopQa.ChromeActionOutcome(true, true, false) },
            dispatchBack = { _, _ -> backs++; DebugOneStopQa.ChromeActionOutcome(true, true, false) },
            allowed = { true },
            diagnosticOnly = false,
            onPhase = {},
            pause = {},
        )

        assertNull(ready.failure)
        assertEquals(0, backs)
        assertEquals(0, ready.omniboxBackAttempts)
    }

    @Test fun p0a_refusesBackForNonBlankUnsafeIncognitoOrUnprovenSemantics() {
        val draftUrl = blankFocusedUrl.copy(text = "user draft", empty = false)
        assertFalse(DebugOneStopQa.mayDispatchGuardedOmniboxBack(
            frame(draftUrl, suggestions, editor = draftUrl, withIme = true)
        ))
        val pwUrl = blankFocusedUrl.copy(password = true)
        assertFalse(DebugOneStopQa.mayDispatchGuardedOmniboxBack(
            frame(pwUrl, suggestions, editor = pwUrl, withIme = true)
        ))
        // Unfocused url_bar -> refuse
        assertFalse(DebugOneStopQa.mayDispatchGuardedOmniboxBack(
            frame(blankFocusedUrl.copy(focused = false), suggestions, withIme = true)
        ))
        // Neither IME nor suggestions dropdown -> Back semantics not guaranteed -> refuse
        assertFalse(DebugOneStopQa.mayDispatchGuardedOmniboxBack(
            frame(blankFocusedUrl, editor = blankFocusedUrl, withIme = false)
        ))
        // Popup/dialog over Chrome -> refuse
        assertFalse(DebugOneStopQa.mayDispatchGuardedOmniboxBack(
            frame(blankFocusedUrl, suggestions, editor = blankFocusedUrl, withIme = true, foregroundWindowId = 99999)
        ))
        // Active incognito window indicator -> refuse
        assertFalse(DebugOneStopQa.mayDispatchGuardedOmniboxBack(
            frame(blankFocusedUrl, suggestions, node(90, "incognito_badge"), editor = blankFocusedUrl, withIme = true)
        ))
        // Ordinary NTP button labeled '새 시크릿 탭' is NOT incognito-window proof
        assertFalse(DebugOneStopQa.isIncognitoChromeWindow(
            frame(ntp, menu, node(91, clickable = true, text = "새 시크릿 탭"))
        ))
    }

    @Test fun p0a_stopsImmediatelyOnNoDistinctTransitionContextChangeUnknownOrRevocation() = runBlocking {
        val initialFocused = frame(blankFocusedUrl, suggestions, editor = blankFocusedUrl, withIme = true)

        // 1) Back 1 leaves IME still visible -> NOT a distinct state -> never sends Back 2
        run {
            val frames = ArrayDeque(listOf(initialFocused, initialFocused, initialFocused, initialFocused))
            var backs = 0
            val res = DebugOneStopQa.prepareChromeEditorWithSeam(
                capture = { frames.removeFirst() },
                inspectWindow = { chromeFacts() },
                clickNode = { _, _, _ -> DebugOneStopQa.ChromeActionOutcome(true, true, false) },
                dispatchBack = { _, _ -> backs++; DebugOneStopQa.ChromeActionOutcome(true, true, false) },
                allowed = { true },
                diagnosticOnly = false,
                onPhase = {},
                pause = {},
            )
            assertEquals("unique_chrome_menu_unavailable", res.failure)
            assertEquals(1, backs)
            assertFalse(res.unknown)
        }

        // 2) Back 1 returns UNKNOWN -> stops immediately, no Back 2, no replay
        run {
            val frames = ArrayDeque(listOf(initialFocused, initialFocused, initialFocused))
            var backs = 0
            val res = DebugOneStopQa.prepareChromeEditorWithSeam(
                capture = { frames.removeFirst() },
                inspectWindow = { chromeFacts() },
                clickNode = { _, _, _ -> DebugOneStopQa.ChromeActionOutcome(true, true, false) },
                dispatchBack = { _, _ -> backs++; DebugOneStopQa.ChromeActionOutcome(false, true, true) },
                allowed = { true },
                diagnosticOnly = false,
                onPhase = {},
                pause = {},
            )
            assertEquals("chrome_omnibox_back_unconfirmed", res.failure)
            assertEquals(1, backs)
            assertTrue(res.unknown)
        }

        // 3) Window / revision changed after Back 1 -> stops immediately
        run {
            val shifted = frame(ntp, menu, counter(10), windowId = 13399, withIme = false)
            val frames = ArrayDeque(listOf(initialFocused, initialFocused, initialFocused, shifted))
            var backs = 0
            val res = DebugOneStopQa.prepareChromeEditorWithSeam(
                capture = { frames.removeFirst() },
                inspectWindow = { chromeFacts() },
                clickNode = { _, _, _ -> DebugOneStopQa.ChromeActionOutcome(true, true, false) },
                dispatchBack = { _, _ -> backs++; DebugOneStopQa.ChromeActionOutcome(true, true, false) },
                allowed = { true },
                diagnosticOnly = false,
                onPhase = {},
                pause = {},
            )
            assertEquals("chrome_omnibox_back_context_changed", res.failure)
            assertEquals(1, backs)
        }

        // 4) Authority revoked during Back 1 pause -> stops immediately
        run {
            var live = true
            val frames = ArrayDeque(listOf(initialFocused, initialFocused, initialFocused))
            var backs = 0
            val res = DebugOneStopQa.prepareChromeEditorWithSeam(
                capture = { frames.removeFirst() },
                inspectWindow = { chromeFacts() },
                clickNode = { _, _, _ -> DebugOneStopQa.ChromeActionOutcome(true, true, false) },
                dispatchBack = { _, _ -> backs++; DebugOneStopQa.ChromeActionOutcome(true, true, false) },
                allowed = { live },
                diagnosticOnly = false,
                onPhase = {},
                pause = { if (backs == 1) live = false },
            )
            assertEquals("control_authority_revoked", res.failure)
            assertEquals(1, backs)
        }
    }

    private fun validSequenceObservation(vararg nodes: ControlNode, preflightStatus: String = "REVIEW_READY") =
        buildJsonObject {
            put("observation_id", "seq-obs-1")
            put("security_scan_complete", true)
            put("output_truncated", false)
            put("visual", buildJsonObject {
                put("scope", "keyboard")
                put("visual_id", "vis-seq-1")
            })
            put("keyboard_preflight", buildJsonObject {
                put("status", preflightStatus)
                put("reason_code", if (preflightStatus == "REVIEW_READY") "image_rect_review_required" else "physical_keys_not_unique")
                put("effective_empty_caret_zero_verified", true)
            })
            put("nodes", JsonArray(nodes.map { it.json() }))
        }

    @Test fun p0b_sixKeyExecutionReusesVisualKeySequenceOnlyWhenAllFourKeysPixelCertified() = runBlocking {
        val g = node(101, window = 13301, pkg = ime, keyboardKey = "ㄱ", visualKeyCandidate = true)
        val a = node(103, window = 13301, pkg = ime, keyboardKey = "ㅏ", visualKeyCandidate = true)
        val n = node(105, window = 13301, pkg = ime, keyboardKey = "ㄴ", visualKeyCandidate = true)
        val space = node(107, window = 13301, pkg = ime, keyboardImageCandidate = true)
        val initial = frame(blankFocusedUrl, g, a, n, space, editor = blankFocusedUrl, withIme = true)
        val finalEditor = blankFocusedUrl.copy(text = "가 나 ", empty = false, selectionStart = 4, selectionEnd = 4)
        val afterSeq = frame(finalEditor, editor = finalEditor, withIme = true)

        val frames = ArrayDeque(listOf(initial, afterSeq))
        var sequenceCalls = 0
        val outcome = DebugSixKeyExecution.runWithSeam(
            captureFrame = { frames.removeFirst() },
            observeSequence = { DebugSixKeyExecution.ObservePacket(true, validSequenceObservation(g, a, n, space)) },
            certifyCandidate = { _, _, _ -> TapBounds(12, 12, 80, 80) },
            dispatchSequence = { payload ->
                sequenceCalls++
                assertEquals("가 나 ", payload["text"]?.jsonPrimitive?.content)
                assertEquals(true, payload["visual_target_confirmed"]?.jsonPrimitive?.booleanOrNull)
                assertEquals(6, payload["steps"]?.jsonArray?.size)
                DebugSixKeyExecution.SequencePacket(true, buildJsonObject {
                    put("action_attempted", true)
                    put("outcome_unknown", false)
                    put("app_result_verified", true)
                    put("completed_keys", 6)
                    put("confirmed_image_fallback_spaces", 2)
                })
            },
            sessionId = "s1",
            first = initial,
            allowed = { true },
            checkpoint = { _, _ -> },
            clockMs = { 100L },
        )

        assertNull(outcome.failure)
        assertEquals(1, sequenceCalls)
        assertEquals(6, outcome.progress.verified)
        assertEquals(6, outcome.progress.attempts)
        assertFalse(outcome.progress.uncertain)
    }

    @Test fun p0b_duplicateNonEntryLabelsMissingSpaceOrUncertifiedPixelsFailClosedWithZeroTouches() = runBlocking {
        val g = node(101, window = 13301, pkg = ime, keyboardKey = "ㄱ", visualKeyCandidate = true)
        val gDup = node(102, window = 13301, pkg = ime, keyboardKey = "ㄱ", visualKeyCandidate = true)
        val gNonVisual = node(109, window = 13301, pkg = ime, keyboardKey = "ㄱ", visualKeyCandidate = false)
        val a = node(103, window = 13301, pkg = ime, keyboardKey = "ㅏ", visualKeyCandidate = true)
        val n = node(105, window = 13301, pkg = ime, keyboardKey = "ㄴ", visualKeyCandidate = true)
        val space = node(107, window = 13301, pkg = ime, keyboardImageCandidate = true)
        val initial = frame(blankFocusedUrl, editor = blankFocusedUrl, withIme = true)

        // 1) Duplicate visual candidates or non-entry nodes without visualKeyCandidate -> rejected
        assertNull(DebugSixKeyExecution.resolveUniqueSequenceCandidates(validSequenceObservation(g, gDup, a, n, space)))
        assertNull(DebugSixKeyExecution.resolveUniqueSequenceCandidates(validSequenceObservation(gNonVisual, a, n, space)))
        // 2) Missing Space fallback -> rejected
        assertNull(DebugSixKeyExecution.resolveUniqueSequenceCandidates(validSequenceObservation(g, a, n)))
        // 3) Blocked keyboard_preflight -> rejected
        assertNull(DebugSixKeyExecution.resolveUniqueSequenceCandidates(
            validSequenceObservation(g, a, n, space, preflightStatus = "BLOCKED")
        ))

        // 4) Uncertified pixel roles (Space passes, ㄱ/ㅏ/ㄴ fail OCR) -> fail closed with 0 touches!
        var dispatched = 0
        val frames = ArrayDeque(listOf(initial, initial))
        val uncertified = DebugSixKeyExecution.runWithSeam(
            captureFrame = { frames.removeFirst() },
            observeSequence = { DebugSixKeyExecution.ObservePacket(true, validSequenceObservation(g, a, n, space)) },
            certifyCandidate = { _, _, c -> if (c.label == "Space") TapBounds(12, 12, 80, 80) else null },
            dispatchSequence = {
                dispatched++
                DebugSixKeyExecution.SequencePacket(false, buildJsonObject {})
            },
            sessionId = "s1",
            first = initial,
            allowed = { true },
            checkpoint = { _, _ -> },
            clockMs = { 100L },
        )

        assertEquals("autonomous_pixel_role_unverified", uncertified.failure)
        assertEquals(0, dispatched)
        assertEquals(0, uncertified.progress.attempts)
        assertFalse(uncertified.progress.uncertain)
        val pixelCheck = uncertified.evidence.first {
            it["source"]?.jsonPrimitive?.contentOrNull == "autonomous_pixel_role_check"
        }
        assertFalse(pixelCheck["pixel_role_certified"]?.jsonPrimitive?.boolean!!)
        assertEquals(listOf("Space"), pixelCheck["verified_labels"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(listOf("ㄱ", "ㅏ", "ㄴ"), pixelCheck["unverified_labels"]?.jsonArray?.map { it.jsonPrimitive.content })

        // 5) UNKNOWN from visualKeySequence -> stops immediately, uncertain=true, no replay
        var unknownCalls = 0
        val unknownRes = DebugSixKeyExecution.runWithSeam(
            captureFrame = { initial },
            observeSequence = { DebugSixKeyExecution.ObservePacket(true, validSequenceObservation(g, a, n, space)) },
            certifyCandidate = { _, _, _ -> TapBounds(12, 12, 80, 80) },
            dispatchSequence = {
                unknownCalls++
                DebugSixKeyExecution.SequencePacket(false, buildJsonObject {
                    put("action_attempted", true)
                    put("outcome_unknown", true)
                    put("completed_keys", 2)
                    put("reason_code", "keyboard_input_unconfirmed")
                })
            },
            sessionId = "s1",
            first = initial,
            allowed = { true },
            checkpoint = { _, _ -> },
            clockMs = { 100L },
        )
        assertEquals("keyboard_input_unconfirmed", unknownRes.failure)
        assertEquals(1, unknownCalls)
        assertTrue(unknownRes.progress.uncertain)
    }

    @Test fun p1_terminalBlockedOrUnknownAlwaysRecordsNonZeroFailureAndStepEvent() {
        val recorded = DebugOneStopQa.ensureTerminalFailureRecorded(
            currentFailureCount = 0,
            currentFirstFailureCode = null,
            currentStepEventCount = 0,
            reason = "unique_physical_key_not_proven",
        )
        assertEquals(1, recorded.failureCount)
        assertEquals("unique_physical_key_not_proven", recorded.firstFailureCode)
        assertTrue(recorded.shouldRecordStep)

        val alreadyRecorded = DebugOneStopQa.ensureTerminalFailureRecorded(
            currentFailureCount = 1,
            currentFirstFailureCode = "unique_chrome_menu_unavailable",
            currentStepEventCount = 2,
            reason = "unique_chrome_menu_unavailable",
        )
        assertEquals(1, alreadyRecorded.failureCount)
        assertEquals("unique_chrome_menu_unavailable", alreadyRecorded.firstFailureCode)
        assertFalse(alreadyRecorded.shouldRecordStep)

        val sanitized = DebugOneStopQa.ensureTerminalFailureRecorded(
            currentFailureCount = 0,
            currentFirstFailureCode = null,
            currentStepEventCount = 0,
            reason = "https://private.invalid/leak",
        )
        assertEquals("unavailable", sanitized.firstFailureCode)
        assertEquals(1, sanitized.failureCount)
        assertTrue(sanitized.shouldRecordStep)
    }

    @Test fun p0a_freshBackObservationRevalidatesBlankFocusedEditorAndContext() {
        val base = frame(blankFocusedUrl, suggestions, editor = blankFocusedUrl, withIme = true)
        fun obs(
            bar: ControlNode = blankFocusedUrl,
            fgWindow: Int = 13300,
            withKb: Boolean = true,
            nav: List<String> = listOf("home", "back", "open_app"),
        ) = buildJsonObject {
            put("observation_id", "obs-1")
            put("output_truncated", false)
            put("security_scan_complete", true)
            put("truncated", false)
            put("navigation_only", false)
            put("node_actions_allowed", true)
            put("package_name", chrome)
            put("window_id", 13300)
            put("foreground_window_id", fgWindow)
            put("configuration_revision", 139L)
            put("navigation_actions", JsonArray(nav.map(::JsonPrimitive)))
            if (withKb) {
                put("keyboard", buildJsonObject {
                    put("window_id", keyboard.windowId)
                    put("package_name", keyboard.packageName)
                })
            }
            put("nodes", JsonArray(listOf(bar.json())))
        }

        assertTrue(DebugOneStopQa.guardedBackObservationMatches(base, obs()))
        // Newly typed draft between frame and fresh observe -> refused!
        assertFalse(DebugOneStopQa.guardedBackObservationMatches(base, obs(bar = blankFocusedUrl.copy(text = "draft", empty = false))))
        // Lost focus -> refused!
        assertFalse(DebugOneStopQa.guardedBackObservationMatches(base, obs(bar = blankFocusedUrl.copy(focused = false))))
        // Foreground window changed -> refused!
        assertFalse(DebugOneStopQa.guardedBackObservationMatches(base, obs(fgWindow = 99999)))
        // Keyboard state drifted -> refused!
        assertFalse(DebugOneStopQa.guardedBackObservationMatches(base, obs(withKb = false)))
        // Back not in navigation_actions -> refused!
        assertFalse(DebugOneStopQa.guardedBackObservationMatches(base, obs(nav = listOf("home", "open_app"))))
    }
}
