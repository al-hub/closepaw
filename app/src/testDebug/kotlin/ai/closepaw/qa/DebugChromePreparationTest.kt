package ai.closepaw.qa

import ai.closepaw.chatgpt.control.ControlFrame
import ai.closepaw.chatgpt.control.ControlKeyboard
import ai.closepaw.chatgpt.control.ControlNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Closed-loop regression and safety-invariant tests for #147 Chrome preparation.
 * Exercises DebugOneStopQa.prepareChromeEditorWithSeam directly in JVM unit tests
 * without relaxing native authority, window ownership, or UNKNOWN non-replay rules.
 */
class DebugChromePreparationTest {
    private val chrome = "com.android.chrome"
    private val ime = "com.samsung.android.honeyboard"
    private val keyboard = ControlKeyboard(8, ime, "qa-ime")

    private fun node(
        id: Int,
        role: String = "",
        window: Int = 7,
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
    ) = ControlNode(
        id = id,
        resourceId = if (role.isEmpty()) "" else "$pkg:id/$role",
        sourceWindowId = window,
        sourcePackage = pkg,
        bounds = "0 0 100 100",
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
    )

    private fun blankUrl(id: Int = 1, window: Int = 7) = node(
        id = id, role = "url_bar", window = window,
        clickable = true, editable = true, focused = true,
        selectionStart = 0, selectionEnd = 0,
    )

    private fun blankNtp(id: Int = 6, window: Int = 7) = node(
        id = id, role = "search_box_text", window = window,
        clickable = true, editable = false,
    )

    private fun menuBtn(id: Int = 2, window: Int = 7) = node(
        id = id, role = "menu_button", window = window, clickable = true,
    )

    private fun tabCounter(count: Int, id: Int = 3, window: Int = 7) = node(
        id = id, role = "tab_switcher_button", window = window,
        clickable = true, description = "$count tabs",
    )

    private fun directNewTab(id: Int = 7, role: String = "new_tab_button", description: String = "New tab", window: Int = 7) = node(
        id = id, role = role, window = window, clickable = true, description = description,
    )

    private fun popupFrame(window: Int = 7): ControlFrame {
        val item = node(4, role = "new_tab_menu_id", window = window, clickable = true)
        val label = node(5, role = "menu_item_text", window = window, parent = 4, text = "새 탭")
        return frame(item, label, windowId = window)
    }

    private fun frame(
        vararg nodes: ControlNode,
        editor: ControlNode? = null,
        windowId: Int = 7,
        pkg: String = chrome,
        reason: String? = null,
        truncated: Boolean = false,
        navigationOnly: Boolean = false,
    ) = ControlFrame(
        packageName = pkg,
        windowId = windowId,
        fingerprint = "fp-$windowId",
        nodes = nodes.toList(),
        reason = reason,
        truncated = truncated,
        navigationOnly = navigationOnly,
        inputTarget = editor,
        keyboard = keyboard.takeIf { editor != null },
    )

    private fun chromeFacts(chromeRoot: Boolean = true) = DebugOneStopQa.ChromeWindowFacts(
        chromeRoot = chromeRoot,
        rootWindowFound = true,
        applicationWindow = true,
        active = true,
        focused = true,
        chromeOwned = chromeRoot,
        rootPresent = true,
        pictureInPicture = false,
    )

    @Test fun retriesReadOnlyWhenMenuIsInitiallyMissingAfterSecureWindowChanged() = runBlocking {
        // Mirrors #147 comment 7125340658 aggregate profile:
        // read 1 secure_window_changed -> read 2 full Chrome frame (61 nodes, 1 blank url_bar, 0 menu)
        // -> read 3 toolbar settled with menu_button within existing 3-read bound.
        val url = blankUrl()
        val frames = ArrayDeque(listOf(
            frame(reason = "secure_window_changed"),
            frame(url, *Array(60) { node(it + 100) }, editor = url),
            frame(url, menuBtn(), tabCounter(4), editor = url),
            popupFrame(),
            frame(blankNtp(), tabCounter(5)),
            frame(url, tabCounter(5), editor = url),
        ))
        val clickedIds = mutableListOf<Int>()
        val pauses = mutableListOf<Long>()

        val ready = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = { frames.removeFirst() },
            inspectWindow = { chromeFacts() },
            clickNode = { _, target, _ ->
                clickedIds += target.id
                DebugOneStopQa.ChromeNodeClickResult(confirmed = true, attempted = true, unknown = false)
            },
            allowed = { true },
            diagnosticOnly = false,
            onPhase = {},
            pause = { pauses += it },
        )

        assertNull(ready.failure)
        assertNotNull(ready.frame)
        // 3 reads in initial preflight boundary (1 initial + 2 recoveries) + 1 post-focus settle read = 4
        assertEquals(4, ready.chromeReadCount)
        assertEquals(2, ready.chromeRecoveryReads)
        assertEquals("secure_window_changed", ready.firstChromeRefusal)
        assertTrue(ready.newTabVerified)
        assertEquals("tab_count_increment_and_blank_ntp", ready.newTabEvidence)
        assertEquals(listOf(2, 4, 6), clickedIds)
        assertEquals(2, ready.navigationClicks)
        assertEquals(1, ready.focusClicks)
        assertFalse(ready.unknown)
        assertNull(DebugOneStopQa.editorPreflightReason(DebugOneStopQa.editorPreflightFacts(ready.frame!!)))
    }

    @Test fun retriesReadOnlyWhenDirectNewTabIsInitiallyMissing() = runBlocking {
        val url = blankUrl()
        val frames = ArrayDeque(listOf(
            frame(url, *Array(10) { node(it + 100) }, editor = url),
            frame(url, *Array(10) { node(it + 200) }, editor = url),
            frame(url, directNewTab(), tabCounter(4), editor = url),
            frame(blankNtp(), tabCounter(5)),
            frame(url, tabCounter(5), editor = url),
        ))
        val clickedIds = mutableListOf<Int>()

        val ready = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = { frames.removeFirst() },
            inspectWindow = { chromeFacts() },
            clickNode = { _, target, _ ->
                clickedIds += target.id
                DebugOneStopQa.ChromeNodeClickResult(confirmed = true, attempted = true, unknown = false)
            },
            allowed = { true },
            diagnosticOnly = false,
            onPhase = {},
            pause = {},
        )

        assertNull(ready.failure)
        // 3 reads in initial preflight boundary (1 initial + 2 recoveries) + 1 post-focus settle read = 4
        assertEquals(4, ready.chromeReadCount)
        assertEquals(2, ready.chromeRecoveryReads)
        assertTrue(ready.newTabVerified)
        assertEquals("direct_new_tab_count_increment_and_blank_ntp", ready.newTabEvidence)
        assertEquals(listOf(7, 6), clickedIds)
        assertEquals(1, ready.navigationClicks)
        assertEquals(1, ready.focusClicks)
    }

    @Test fun toolbarRecoveryStopsAtThreeReadsWithZeroActions() = runBlocking {
        val url = blankUrl()
        // Queue a 4th frame with menu_button to prove the 4th read is NEVER performed.
        val frames = ArrayDeque(listOf(
            frame(url, *Array(60) { node(it + 100) }, editor = url),
            frame(url, *Array(60) { node(it + 200) }, editor = url),
            frame(url, *Array(60) { node(it + 300) }, editor = url),
            frame(url, menuBtn(), tabCounter(4), editor = url),
        ))
        var clicks = 0

        val ready = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = { frames.removeFirst() },
            inspectWindow = { chromeFacts() },
            clickNode = { _, _, _ ->
                clicks++
                DebugOneStopQa.ChromeNodeClickResult(confirmed = true, attempted = true, unknown = false)
            },
            allowed = { true },
            diagnosticOnly = false,
            onPhase = {},
            pause = {},
        )

        assertEquals("unique_chrome_menu_unavailable", ready.failure)
        assertNull(ready.frame)
        assertEquals(3, ready.chromeReadCount)
        assertEquals(2, ready.chromeRecoveryReads)
        assertEquals(1, frames.size) // 4th frame untouched
        assertEquals(0, clicks)
        assertEquals(0, ready.navigationClicks)
        assertEquals(0, ready.focusClicks)
        assertFalse(ready.unknown)
    }

    @Test fun toolbarRecoveryNeverBypassesSafetyGatesOrAuthorityRevocation() = runBlocking {
        val gatedCases = listOf(
            "preflight_frame_unavailable" to frame(reason = "secure_window"),
            "preflight_frame_truncated" to frame(blankUrl(), truncated = true),
            "preflight_navigation_only" to frame(blankUrl(), navigationOnly = true),
            "preflight_chrome_not_foreground" to frame(blankUrl(), pkg = "other.app"),
        )
        for ((expectedFailure, firstFrame) in gatedCases) {
            var reads = 0
            var clicks = 0
            val ready = DebugOneStopQa.prepareChromeEditorWithSeam(
                capture = {
                    reads++
                    if (reads == 1) firstFrame else frame(blankUrl(), menuBtn())
                },
                inspectWindow = { chromeFacts() },
                clickNode = { _, _, _ ->
                    clicks++
                    DebugOneStopQa.ChromeNodeClickResult(true, true, false)
                },
                allowed = { true },
                diagnosticOnly = false,
                onPhase = {},
                pause = {},
            )
            assertEquals(expectedFailure, ready.failure)
            assertEquals("Safety gate must not resample: $expectedFailure", 1, reads)
            assertEquals(0, clicks)
        }

        // Authority revoked during toolbar settle pause must stop before read 2.
        var live = true
        var reads = 0
        var clicks = 0
        val revoked = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = { reads++; frame(blankUrl()) },
            inspectWindow = { chromeFacts() },
            clickNode = { _, _, _ ->
                clicks++
                DebugOneStopQa.ChromeNodeClickResult(true, true, false)
            },
            allowed = { live },
            diagnosticOnly = false,
            onPhase = {},
            pause = { live = false },
        )
        assertEquals("control_authority_revoked", revoked.failure)
        assertEquals(1, reads)
        assertEquals(0, clicks)
    }

    @Test fun directNativeNewTabAcceptsOnlyUniqueOwnedSafeSelectors() {
        assertEquals(7, DebugOneStopQa.chromeDirectNewTabButton(
            frame(directNewTab(id = 7, role = "new_tab_button", description = "New tab"))
        )?.id)
        assertEquals(8, DebugOneStopQa.chromeDirectNewTabButton(
            frame(directNewTab(id = 8, role = "toolbar_new_tab_button", description = "새 탭"))
        )?.id)
        assertEquals(9, DebugOneStopQa.chromeDirectNewTabButton(
            frame(node(9, clickable = true, description = "New tab"))
        )?.id)
        assertEquals(10, DebugOneStopQa.chromeDirectNewTabButton(
            frame(node(10, clickable = true, text = "새 탭"))
        )?.id)

        // Unsafe / ambiguous candidates MUST be rejected.
        val rejectedFrames = listOf(
            frame(directNewTab(description = "New Incognito tab")),
            frame(directNewTab(description = "새 시크릿 탭")),
            frame(node(11, clickable = true, description = "New tab in group")),
            frame(node(12, clickable = true, description = "Close tab")),
            frame(node(13, clickable = true, text = "방문 기록")),
            frame(directNewTab(id = 14), directNewTab(id = 15)),
            frame(node(16, clickable = true, description = "New tab"), node(17, clickable = true, text = "새 탭")),
            frame(directNewTab(window = 99)),
            frame(node(18, role = "new_tab_button", pkg = "other.app", clickable = true, description = "New tab")),
            frame(directNewTab().copy(clickable = false)),
            frame(directNewTab().copy(enabled = false)),
            frame(directNewTab().copy(password = true)),
        )
        for (f in rejectedFrames) {
            assertNull("Expected rejection for $f", DebugOneStopQa.chromeDirectNewTabButton(f))
        }
    }

    @Test fun menuButtonWrapperFallbackRequiresUniqueOwnedEligibleControl() {
        val innerNonClickable = node(2, role = "menu_button", clickable = false, enabled = true)
        val wrapper = node(20, role = "menu_button_wrapper", clickable = true, enabled = true)
        assertEquals(20, DebugOneStopQa.chromeMenuButton(frame(innerNonClickable, wrapper))?.id)

        // Duplicate wrapper rejected
        assertNull(DebugOneStopQa.chromeMenuButton(frame(wrapper, wrapper.copy(id = 21))))
        // Foreign window wrapper rejected
        assertNull(DebugOneStopQa.chromeMenuButton(frame(wrapper.copy(sourceWindowId = 99))))
        // Duplicate eligible menu_button must NOT fall through to wrapper
        val m1 = menuBtn(id = 2)
        val m2 = menuBtn(id = 3)
        assertNull(DebugOneStopQa.chromeMenuButton(frame(m1, m2, wrapper)))

        // Redacted selector facts expose structural counts without sensitive content
        val facts = DebugOneStopQa.chromeSelectorFacts(frame(wrapper, directNewTab()))
        assertEquals(1, facts.menuWrapperEligibleCount)
        assertEquals(1, facts.directNewTabEligibleCount)
        val json = facts.redactedJson().toString()
        assertTrue(json.contains("\"direct_new_tab_eligible_matches\":1"))
        assertTrue(json.contains("\"menu_wrapper_eligible_matches\":1"))
        assertFalse(json.contains("New tab"))
    }

    @Test fun countLessFallbackRequiresPreviouslyAbsentBlankNtp() = runBlocking {
        // Case A: started on blank url_bar (blank NTP absent) + no tab counter -> blank NTP appears -> PASS
        val url = blankUrl()
        val framesAbsentBefore = ArrayDeque(listOf(
            frame(url, directNewTab(), editor = url),
            frame(blankNtp()),
            frame(url, editor = url),
        ))
        val ok = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = { framesAbsentBefore.removeFirst() },
            inspectWindow = { chromeFacts() },
            clickNode = { _, _, _ -> DebugOneStopQa.ChromeNodeClickResult(true, true, false) },
            allowed = { true },
            diagnosticOnly = false,
            onPhase = {},
            pause = {},
        )
        assertNull(ok.failure)
        assertFalse(ok.newTabVerified)
        assertEquals("direct_new_tab_previously_absent_blank_ntp", ok.newTabEvidence)

        // Case B: started ALREADY on blank NTP + no tab counter -> unchanged blank NTP -> REJECTED
        val framesAlreadyNtp = ArrayDeque(listOf(
            frame(blankNtp(), menuBtn()),
            popupFrame(),
            frame(blankNtp()),
            frame(blankNtp()),
            frame(blankNtp()),
        ))
        val rejected = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = { framesAlreadyNtp.removeFirst() },
            inspectWindow = { chromeFacts() },
            clickNode = { _, _, _ -> DebugOneStopQa.ChromeNodeClickResult(true, true, false) },
            allowed = { true },
            diagnosticOnly = false,
            onPhase = {},
            pause = {},
        )
        assertEquals("new_blank_chrome_tab_unverified", rejected.failure)
        assertNull(rejected.frame)

        // Case C: started ALREADY on blank NTP, WITH tab counter incrementing 4 -> 5 -> PASS
        val framesWithCount = ArrayDeque(listOf(
            frame(blankNtp(), directNewTab(), tabCounter(4)),
            frame(blankNtp(), tabCounter(5)),
            frame(url, tabCounter(5), editor = url),
        ))
        val counted = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = { framesWithCount.removeFirst() },
            inspectWindow = { chromeFacts() },
            clickNode = { _, _, _ -> DebugOneStopQa.ChromeNodeClickResult(true, true, false) },
            allowed = { true },
            diagnosticOnly = false,
            onPhase = {},
            pause = {},
        )
        assertNull(counted.failure)
        assertTrue(counted.newTabVerified)
    }

    @Test fun postFocusVerifiesSameWindowAndChromeAddressEditor() = runBlocking {
        // Window changed after focus click -> rejected
        val url = blankUrl(window = 7)
        val switchedUrl = blankUrl(window = 8)
        val framesWindowChanged = ArrayDeque(listOf(
            frame(url, directNewTab(window = 7), tabCounter(4, window = 7), editor = url, windowId = 7),
            frame(blankNtp(window = 7), tabCounter(5, window = 7), windowId = 7),
            frame(switchedUrl, tabCounter(5, window = 8), editor = switchedUrl, windowId = 8),
        ))
        val changed = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = { framesWindowChanged.removeFirst() },
            inspectWindow = { chromeFacts() },
            clickNode = { _, _, _ -> DebugOneStopQa.ChromeNodeClickResult(true, true, false) },
            allowed = { true },
            diagnosticOnly = false,
            onPhase = {},
            pause = {},
        )
        assertEquals("chrome_editor_window_changed", changed.failure)
        assertNull(changed.frame)

        // Same window + url_bar or search_box_text -> PASS
        assertNull(DebugOneStopQa.editorPreflightReason(
            DebugOneStopQa.editorPreflightFacts(frame(url, editor = url, windowId = 7))
        ))
        val focusedSearchBox = blankNtp(window = 7).copy(
            editable = true, focused = true, selectionStart = 0, selectionEnd = 0,
        )
        assertNull(DebugOneStopQa.editorPreflightReason(
            DebugOneStopQa.editorPreflightFacts(frame(focusedSearchBox, editor = focusedSearchBox, windowId = 7))
        ))

        // Foreign sourceWindowId or non-address Chrome editor -> preflight_editor_owner_mismatch
        assertEquals(
            "preflight_editor_owner_mismatch",
            DebugOneStopQa.editorPreflightReason(
                DebugOneStopQa.editorPreflightFacts(frame(url, editor = url.copy(sourceWindowId = 99), windowId = 7))
            ),
        )
        val webInput = node(
            55, role = "web_form_input", window = 7,
            clickable = true, editable = true, focused = true, selectionStart = 0, selectionEnd = 0,
        )
        assertEquals(
            "preflight_editor_owner_mismatch",
            DebugOneStopQa.editorPreflightReason(
                DebugOneStopQa.editorPreflightFacts(frame(url, webInput, editor = webInput, windowId = 7))
            ),
        )
    }

    @Test fun unknownOutcomeNeverReplaysAndDiagnosticModeStaysReadOnly() = runBlocking {
        // UNKNOWN on direct New Tab click stops immediately with zero follow-up clicks or probes.
        val url = blankUrl()
        var captures = 0
        var clicks = 0
        val unknownResult = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = { captures++; frame(url, directNewTab(), tabCounter(4), editor = url) },
            inspectWindow = { chromeFacts() },
            clickNode = { _, _, _ ->
                clicks++
                DebugOneStopQa.ChromeNodeClickResult(confirmed = false, attempted = true, unknown = true)
            },
            allowed = { true },
            diagnosticOnly = false,
            onPhase = {},
            pause = {},
        )
        assertTrue(unknownResult.unknown)
        assertEquals("chrome_new_tab_unconfirmed", unknownResult.failure)
        assertEquals(1, captures)
        assertEquals(1, clicks)
        assertFalse(DebugContinuousReadOnly.mayInspect("chrome_new_tab_unconfirmed", true, true, true, true))

        // Diagnostic-only mode still accepts existing verified blank editor on read 1 without menu or clicks.
        var diagReads = 0
        var diagClicks = 0
        val diag = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = { diagReads++; frame(url, editor = url) },
            inspectWindow = { chromeFacts() },
            clickNode = { _, _, _ ->
                diagClicks++
                DebugOneStopQa.ChromeNodeClickResult(true, true, false)
            },
            allowed = { true },
            diagnosticOnly = true,
            onPhase = {},
            pause = {},
        )
        assertNull(diag.failure)
        assertEquals(1, diagReads)
        assertEquals(0, diagClicks)
        assertEquals("existing_empty_editor_read_only", diag.newTabEvidence)
    }

    @Test fun transientPopupRefusalResamplesReadOnlyOnceWithoutReplayingMenuClick() = runBlocking {
        val url = blankUrl()
        val frames = ArrayDeque(listOf(
            frame(url, menuBtn(), tabCounter(4), editor = url),
            frame(reason = "secure_window_changed"),
            popupFrame(),
            frame(blankNtp(), tabCounter(5)),
            frame(url, tabCounter(5), editor = url),
        ))
        val clickedIds = mutableListOf<Int>()

        val ready = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = { frames.removeFirst() },
            inspectWindow = { chromeFacts() },
            clickNode = { _, target, _ ->
                clickedIds += target.id
                DebugOneStopQa.ChromeNodeClickResult(confirmed = true, attempted = true, unknown = false)
            },
            allowed = { true },
            diagnosticOnly = false,
            onPhase = {},
            pause = {},
        )

        assertNull(ready.failure)
        assertEquals(listOf(2, 4, 6), clickedIds) // menu clicked once, never replayed
        assertEquals(2, ready.navigationClicks)
        assertEquals(1, ready.focusClicks)
    }

    @Test fun authorityRevokedAfterMenuClickBlocksPopupCapture() = runBlocking {
        val url = blankUrl()
        var live = true
        var captures = 0
        val ready = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = {
                captures++
                frame(url, menuBtn(), tabCounter(4), editor = url)
            },
            inspectWindow = { chromeFacts() },
            clickNode = { _, _, _ ->
                live = false // revoked during/immediately after menu click
                DebugOneStopQa.ChromeNodeClickResult(confirmed = true, attempted = true, unknown = false)
            },
            allowed = { live },
            diagnosticOnly = false,
            onPhase = {},
            pause = {},
        )
        assertEquals("control_authority_revoked", ready.failure)
        assertEquals(1, captures) // popup capture MUST NOT execute after revocation
        assertEquals(1, ready.navigationClicks)
    }

    @Test fun incognitoChromeWindowRefusedWhileRegularMenuWithIncognitoOptionAllowed() = runBlocking {
        // Active incognito window via tab switcher description or incognito_badge -> refused on read 1
        val incognitoSwitcher = node(
            3, role = "tab_switcher_button", clickable = true, description = "2 incognito tabs",
        )
        val incognitoFrame = frame(blankUrl(), directNewTab(), incognitoSwitcher, editor = blankUrl())
        assertNull(DebugOneStopQa.chromeTabCount(incognitoFrame))
        assertNull(DebugOneStopQa.chromeDirectNewTabButton(incognitoFrame))
        assertNull(DebugOneStopQa.chromeMenuButton(incognitoFrame))

        var reads = 0
        var clicks = 0
        val refused = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = { reads++; incognitoFrame },
            inspectWindow = { chromeFacts() },
            clickNode = { _, _, _ ->
                clicks++
                DebugOneStopQa.ChromeNodeClickResult(true, true, false)
            },
            allowed = { true },
            diagnosticOnly = false,
            onPhase = {},
            pause = {},
        )
        assertEquals("preflight_incognito_chrome_refused", refused.failure)
        assertEquals(1, reads) // no toolbar retry on incognito window
        assertEquals(0, clicks)

        // Incognito badge indicator also refused
        val badgeFrame = frame(blankUrl(), menuBtn(), node(30, role = "incognito_badge"))
        assertTrue(DebugOneStopQa.isIncognitoChromeFrame(badgeFrame))
        assertNull(DebugOneStopQa.chromeMenuButton(badgeFrame))

        // Normal Chrome popup containing BOTH regular New tab AND New Incognito tab option STILL works
        val regularItem = node(4, role = "new_tab_menu_id", clickable = true)
        val regularLabel = node(5, role = "menu_item_text", parent = 4, text = "New tab")
        val incognitoItem = node(40, role = "new_incognito_tab_menu_id", clickable = true)
        val incognitoLabel = node(41, role = "menu_item_text", parent = 40, text = "New Incognito tab")
        val mixedPopup = frame(regularItem, regularLabel, incognitoItem, incognitoLabel)
        assertEquals(4, DebugOneStopQa.chromeNewTabMenuItem(mixedPopup)?.id)

        // Popup with active incognito window indicator is refused before selection
        val incognitoPopup = frame(regularItem, regularLabel, node(42, role = "incognito_badge"))
        assertNull(DebugOneStopQa.chromeNewTabMenuItem(incognitoPopup))
    }
}
