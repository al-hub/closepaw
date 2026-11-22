package ai.closepaw.qa

import org.junit.Assert.*
import org.junit.Test

class DebugOneStopQaTest {
    @Test fun sixKeyCompositionAndIndependentCaretAreExact() {
        val expected = listOf("", "ㄱ", "가", "가 ", "가 ㄴ", "가 나", "가 나 ")
        assertEquals(expected, (0..6).map(DebugOneStopQa.SixKey::expected))
        for (i in expected.indices) {
            assertTrue(DebugOneStopQa.SixKey.matches(expected[i], expected[i].length,
                expected[i].length, i))
            assertFalse(DebugOneStopQa.SixKey.matches(expected[i], 0, 0, i).takeIf { i > 0 } ?: false)
        }
        assertFalse(DebugOneStopQa.SixKey.matches("가 나", 3, 3, 6))
        assertFalse(DebugOneStopQa.SixKey.matches("가 나 ", 3, 3, 6))
        assertFalse(DebugOneStopQa.SixKey.matches("가 나  ", 5, 5, 6))
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
