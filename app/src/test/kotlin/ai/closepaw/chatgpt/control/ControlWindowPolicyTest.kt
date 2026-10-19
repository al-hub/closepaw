package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class ControlWindowPolicyTest {
    private val host = ControlWindow(10, 0, 3, true, true, true, "example.app")
    private val popup = ControlWindow(11, 0, 5, true, false, false, "example.app", parent = 10)
    @Test fun aNonFocusedOwnedPopupMustReplaceTheUnderlyingActionSurface() {
        val result = ControlWindowPolicy.select(10, "example.app", listOf(host, popup))
        assertNull(result.reason)
        assertEquals("Visible owned popup must not be silently omitted", 11, result.target)
        assertEquals(listOf(10, 11), result.members)
    }
    @Test fun ordinaryForegroundRemainsReadable() {
        val result = ControlWindowPolicy.select(10, "example.app", listOf(host))
        assertNull(result.reason); assertEquals(10, result.target)
    }
    @Test fun missingOrUnfocusedForegroundIsRejected() {
        assertNotNull(ControlWindowPolicy.select(10, "example.app", emptyList()).reason)
        assertNotNull(ControlWindowPolicy.select(10, "example.app", listOf(host.copy(focused = false))).reason)
    }
}
