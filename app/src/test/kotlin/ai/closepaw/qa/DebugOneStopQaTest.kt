package ai.closepaw.qa

import ai.closepaw.chatgpt.control.ControlFrame
import ai.closepaw.chatgpt.control.ControlNode
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DebugOneStopQaTest {
    @Test fun debugNativeRefusalPreservesSafeCodeAndRedactsUntrustedDetails() {
        val wanted = listOf("ㄱ", "ㅏ", "ㄴ", "Space")
        val valid = buildJsonObject {
            put("reason_code", "visual_keyboard_policy_denied")
            put("raw_url", "https://private.invalid/token")
        }
        val failure = DebugKeyboardEvidence.fromObservation(valid, false, wanted)
        assertEquals("visual_keyboard_policy_denied",
            failure["native_refusal_reason"]?.jsonPrimitive?.content)
        assertFalse(failure.toString().contains("private.invalid"))
        assertEquals("unavailable", DebugKeyboardEvidence.fromObservation(
            buildJsonObject {}, false, wanted)["native_refusal_reason"]?.jsonPrimitive?.content)
        for (bad in listOf("PRIVATE_TOKEN", "https://secret.example", "contains spaces",
                           "a".repeat(81))) {
            val evidence = DebugKeyboardEvidence.fromObservation(
                buildJsonObject { put("reason_code", bad) }, false, wanted)
            assertEquals("unavailable", evidence["native_refusal_reason"]?.jsonPrimitive?.content)
            assertFalse(evidence.toString().contains(bad))
        }
        val success = DebugKeyboardEvidence.fromObservation(valid, true, wanted)
        assertFalse(success.containsKey("native_refusal_reason"))
        assertEquals("unavailable", DebugKeyboardEvidence.nativeRefusalReason(
            buildJsonObject { put("reason_code", 42) }))
    }

    @Test fun diagnosticOnlyCanObserveExistingVerifiedBlankEditorWithoutChromeMenu() {
        val valid = DebugOneStopQa.EditorPreflightFacts(
            frameReady = true, complete = true, navigationSafe = true,
            chromeForeground = true, keyboardPresent = true, samsungIme = true,
            keyboardReadAllowed = true, keyboardInteractAllowed = true,
            editorPresent = true, chromeEditorOwned = true, editorFocused = true,
            editorEditable = true, nonPassword = true, editorEmpty = true,
            selectionAtOrigin = true)
        assertTrue(DebugOneStopQa.mayUseExistingBlankEditorForReadOnly(valid, true))
        assertFalse(DebugOneStopQa.mayUseExistingBlankEditorForReadOnly(valid, false))
        assertFalse(DebugOneStopQa.mayUseExistingBlankEditorForReadOnly(
            valid.copy(editorEmpty = false), true))
        assertFalse(DebugOneStopQa.mayUseExistingBlankEditorForReadOnly(
            valid.copy(nonPassword = false), true))
        assertFalse(DebugOneStopQa.mayUseExistingBlankEditorForReadOnly(
            valid.copy(chromeForeground = false), true))
        assertFalse(DebugOneStopQa.mayUseExistingBlankEditorForReadOnly(
            valid.copy(selectionAtOrigin = false), true))
        assertFalse(DebugOneStopQa.mayUseExistingBlankEditorForReadOnly(
            valid.copy(complete = false), true))
    }

    @Test fun chromeMenuEvidenceCountsWithoutChangingSafeSelector() {
        val id = "com.android.chrome:id/menu_button"
        val menu = ControlNode(id = 10, resourceId = id, sourcePackage = "com.android.chrome",
            sourceWindowId = 42, clickable = true, enabled = true)
        fun frame(vararg nodes: ControlNode) =
            ControlFrame("com.android.chrome", 42, "f", nodes.toList())
        val absent = DebugOneStopQa.chromeSelectorFacts(frame())
        assertEquals(0, absent.menuIdCount)
        assertEquals(0, absent.menuEligibleCount)
        assertNull(DebugOneStopQa.chromeMenuButton(frame()))
        val foreign = menu.copy(id = 11, sourceWindowId = 43)
        val disabled = menu.copy(id = 12, enabled = false)
        val facts = DebugOneStopQa.chromeSelectorFacts(frame(disabled, foreign))
        assertEquals(1, facts.menuIdCount)
        assertEquals(0, facts.menuEligibleCount)
        assertEquals(1, facts.menuForeignOwnerCount)
        assertNull(DebugOneStopQa.chromeMenuButton(frame(disabled, foreign)))
        assertNotNull(DebugOneStopQa.chromeMenuButton(frame(menu)))
        assertFalse(facts.redactedJson().toString().contains("sourcePackage"))
    }

    @Test fun debugClockUsesTwoExplicitDeviceDomainsWithoutUserData() {
        val facts = DebugOneStopQa.deviceClockEvidence(1800000123456L, 1234567L)
        assertEquals(1800000123456L, facts["epoch_ms"]?.jsonPrimitive?.long)
        assertEquals(1234567L, facts["elapsed_realtime_ms"]?.jsonPrimitive?.long)
        assertEquals(2, facts.size)
    }

    @Test fun sixKeyCompositionAndIndependentCaretAreExact() {
        val expected = listOf("", "ㄱ", "가", "가 ", "가 ㄴ", "가 나", "가 나 ")
        assertEquals(expected, (0..6).map(DebugOneStopQa.SixKey::expected))
        for (i in expected.indices) {
            assertTrue(DebugOneStopQa.SixKey.matches(expected[i], expected[i].length,
                expected[i].length, i))
            if (i > 0) assertFalse(DebugOneStopQa.SixKey.matches(expected[i], 0, 0, i))
        }
        assertFalse(DebugOneStopQa.SixKey.matches("가 나", 3, 3, 6))
        assertFalse(DebugOneStopQa.SixKey.matches("가 나 ", 3, 3, 6))
        assertFalse(DebugOneStopQa.SixKey.matches("가 나  ", 5, 5, 6))
    }

    @Test fun canonicalMixedBenchmarkCannotDrift() {
        assertEquals("오늘 회의는 zoom으로 3시에 시작, 자료는 2개.", DebugOneStopQa.MIXED_29)
        assertEquals(29, DebugOneStopQa.MIXED_29.length)
    }

    @Test fun blankChromeOmniboxRejectsPasswordContentAndAmbiguity() {
        val safe = ControlNode(id = 7, resourceId = "com.android.chrome:id/url_bar",
            sourcePackage = "com.android.chrome", sourceWindowId = 42, editable = true, clickable = true,
            enabled = true, empty = true, text = "")
        fun frame(nodes: List<ControlNode>, pkg: String = "com.android.chrome") =
            ControlFrame(pkg, 42, "fingerprint", nodes)
        assertEquals(7, DebugOneStopQa.blankOmnibox(frame(listOf(safe)))?.id)
        assertNull(DebugOneStopQa.blankOmnibox(frame(listOf(safe.copy(password = true)))))
        assertNull(DebugOneStopQa.blankOmnibox(frame(listOf(safe.copy(text = "existing")))))
        assertNull(DebugOneStopQa.blankOmnibox(frame(listOf(safe.copy(empty = false)))))
        assertNull(DebugOneStopQa.blankOmnibox(frame(listOf(safe, safe.copy(id = 8)))))
        assertNull(DebugOneStopQa.blankOmnibox(frame(listOf(safe), "other.app")))
        assertNull(DebugOneStopQa.blankOmnibox(frame(listOf(safe.copy(
            resourceId = "com.android.chrome:id/search_suggestion")))))
        assertNull(DebugOneStopQa.blankOmnibox(frame(listOf(safe)).copy(truncated = true)))
        assertNull(DebugOneStopQa.blankOmnibox(frame(listOf(safe)).copy(reason = "permission_revoked")))
    }


    @Test fun chromeNtpPreFocusSelectorIsUniqueBlankOwnedAndSafe() {
        val ntp = ControlNode(id = 24, resourceId = "com.android.chrome:id/search_box_text",
            sourcePackage = "com.android.chrome", sourceWindowId = 42, editable = false,
            clickable = true, enabled = true, text = "", empty = true)
        fun frame(vararg nodes: ControlNode) = ControlFrame("com.android.chrome", 42, "f", nodes.toList())
        assertEquals(24, DebugOneStopQa.blankOmnibox(frame(ntp))?.id)
        assertNull(DebugOneStopQa.blankOmnibox(frame(ntp.copy(text = "old", empty = false))))
        assertNull(DebugOneStopQa.blankOmnibox(frame(ntp.copy(password = true))))
        assertNull(DebugOneStopQa.blankOmnibox(frame(ntp.copy(sourceWindowId = 77))))
        assertNull(DebugOneStopQa.blankOmnibox(frame(ntp.copy(sourcePackage = "other.app"))))
        assertNull(DebugOneStopQa.blankOmnibox(frame(ntp, ntp.copy(id = 25))))
        assertNull(DebugOneStopQa.blankOmnibox(frame(ntp.copy(resourceId = "com.android.chrome:id/search_suggestion"))))
        val facts = DebugOneStopQa.chromeSelectorFacts(frame(ntp))
        assertEquals(1, facts.searchBoxCount)
        assertEquals(0, facts.editableCount)
        assertFalse(facts.redactedJson().toString().contains("old"))
    }

    @Test fun onlyProvenNewTabMenuItemMayBeSelected() {
        val button = ControlNode(id = 10, resourceId = "com.android.chrome:id/menu_button",
            sourcePackage = "com.android.chrome", sourceWindowId = 42, clickable = true)
        val item = ControlNode(id = 16, resourceId = "com.android.chrome:id/new_tab_menu_id",
            sourcePackage = "com.android.chrome", sourceWindowId = 42, clickable = true)
        val label = ControlNode(id = 18, parent = 16, resourceId = "com.android.chrome:id/menu_item_text",
            sourcePackage = "com.android.chrome", sourceWindowId = 42, text = "새 탭")
        fun frame(vararg nodes: ControlNode) = ControlFrame("com.android.chrome", 42, "f", nodes.toList())
        assertEquals(10, DebugOneStopQa.chromeMenuButton(frame(button))?.id)
        assertEquals(16, DebugOneStopQa.chromeNewTabMenuItem(frame(item, label))?.id)
        assertNull(DebugOneStopQa.chromeNewTabMenuItem(frame(item)))
        assertNull(DebugOneStopQa.chromeNewTabMenuItem(frame(item, label.copy(text = "새 시크릿 탭"))))
        assertNull(DebugOneStopQa.chromeNewTabMenuItem(frame(item.copy(password = true), label)))
        assertNull(DebugOneStopQa.chromeNewTabMenuItem(frame(item, label, item.copy(id = 20))))
        assertNull(DebugOneStopQa.chromeMenuButton(frame(button.copy(sourceWindowId = 77))))
    }

    @Test fun tabCounterIncrementAndBlankNtpAreIndependentProof() {
        val tab = ControlNode(id = 1, resourceId = "com.android.chrome:id/tab_switcher_button",
            sourcePackage = "com.android.chrome", sourceWindowId = 42, clickable = true,
            description = "8개 탭 보기")
        val ntp = ControlNode(id = 2, resourceId = "com.android.chrome:id/search_box_text",
            sourcePackage = "com.android.chrome", sourceWindowId = 42, clickable = true, text = "", empty = true)
        fun frame(vararg nodes: ControlNode) = ControlFrame("com.android.chrome", 42, "f", nodes.toList())
        val after = frame(tab, ntp)
        assertEquals(8, DebugOneStopQa.chromeTabCount(after))
        assertTrue(DebugOneStopQa.verifiedBlankNewTab(7, after))
        assertFalse(DebugOneStopQa.verifiedBlankNewTab(8, after))
        assertFalse(DebugOneStopQa.verifiedBlankNewTab(6, after))
        assertFalse(DebugOneStopQa.verifiedBlankNewTab(7, frame(tab, ntp.copy(text = "old", empty = false))))
        assertFalse(DebugOneStopQa.verifiedBlankNewTab(7, frame(tab, ntp, ntp.copy(id = 4))))
        assertFalse(DebugOneStopQa.verifiedBlankNewTab(7, after.copy(reason = "secure_window")))
        assertFalse(DebugOneStopQa.verifiedBlankNewTab(7, after.copy(truncated = true)))
        assertNull(DebugOneStopQa.chromeTabCount(frame(tab.copy(description = "wrong"))))
        assertNull(DebugOneStopQa.chromeTabCount(frame(tab.copy(description = "8 tabs 9"))))
    }

    @Test fun preflightHasIndependentNonSensitiveFailureReasons() {
        val ok = DebugOneStopQa.EditorPreflightFacts(
            true, true, true, true, true, true, true, true,
            true, true, true, true, true, true, true)
        assertNull(DebugOneStopQa.editorPreflightReason(ok))
        val cases = listOf(
            "preflight_frame_unavailable" to ok.copy(frameReady = false),
            "preflight_frame_truncated" to ok.copy(complete = false),
            "preflight_navigation_only" to ok.copy(navigationSafe = false),
            "preflight_chrome_not_foreground" to ok.copy(chromeForeground = false),
            "preflight_keyboard_missing" to ok.copy(keyboardPresent = false),
            "preflight_samsung_ime_missing" to ok.copy(samsungIme = false),
            "preflight_keyboard_read_denied" to ok.copy(keyboardReadAllowed = false),
            "preflight_keyboard_interact_denied" to ok.copy(keyboardInteractAllowed = false),
            "preflight_editor_missing" to ok.copy(editorPresent = false),
            "preflight_editor_owner_mismatch" to ok.copy(chromeEditorOwned = false),
            "preflight_editor_not_focused" to ok.copy(editorFocused = false),
            "preflight_editor_not_editable" to ok.copy(editorEditable = false),
            "preflight_password_editor" to ok.copy(nonPassword = false),
            "preflight_editor_not_empty" to ok.copy(editorEmpty = false),
            "preflight_selection_not_origin" to ok.copy(selectionAtOrigin = false),
        )
        cases.forEach { (reason, facts) ->
            assertEquals(reason, DebugOneStopQa.editorPreflightReason(facts))
        }
        assertEquals(15, cases.size)
        assertFalse(ok.redactedJson().toString().contains("text"))
    }

    @Test fun transientReadOnlyCaptureCanResampleButSecurityRefusalsCannot() {
        val canRetry = DebugOneStopQa::readOnlyResampleReason
        assertTrue(canRetry("active_root_missing"))
        assertTrue(canRetry("secure_window_changed"))
        assertTrue(canRetry("screen_changed")) // Read-only fresh scan, never a touch replay.
        listOf(null, "secure_window", "secure_window_probe_failed",
            "secure_window_probe_throttled", "device_locked",
            "screen_share_revoked", "generic_control_disabled",
            "preflight_frame_truncated").forEach { assertFalse(canRetry(it)) }
    }

    @Test fun deniedUnknownOrUnattemptedKeyIsNeverAdvancedOrReplayed() {
        val p = DebugOneStopQa.SixKey
        assertTrue(p.mayContinue(attempted = true, unknown = false, verified = true))
        assertFalse(p.mayContinue(attempted = false, unknown = false, verified = true))
        assertFalse(p.mayContinue(attempted = true, unknown = true, verified = false))
        assertFalse(p.mayContinue(attempted = true, unknown = true, verified = true))
        assertFalse(p.mayContinue(attempted = true, unknown = false, verified = false))
    }
}
