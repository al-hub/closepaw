package ai.closepaw.qa

import ai.closepaw.chatgpt.control.ControlFrame
import ai.closepaw.chatgpt.control.ControlNode
import org.junit.Assert.*
import org.junit.Test

class DebugOneStopQaTest {
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
            sourcePackage = "com.android.chrome", editable = true, clickable = true,
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
