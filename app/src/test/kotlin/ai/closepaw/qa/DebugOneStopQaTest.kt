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

    @Test fun deniedUnknownOrUnattemptedKeyIsNeverAdvancedOrReplayed() {
        val p = DebugOneStopQa.SixKey
        assertTrue(p.mayContinue(attempted = true, unknown = false, verified = true))
        assertFalse(p.mayContinue(attempted = false, unknown = false, verified = true))
        assertFalse(p.mayContinue(attempted = true, unknown = true, verified = false))
        assertFalse(p.mayContinue(attempted = true, unknown = true, verified = true))
        assertFalse(p.mayContinue(attempted = true, unknown = false, verified = false))
    }
}
