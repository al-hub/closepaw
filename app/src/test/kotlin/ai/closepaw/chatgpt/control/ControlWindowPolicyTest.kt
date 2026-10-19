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
    @Test fun anchorOwnershipAlsoIncludesANonFocusedPopup() {
        val result = ControlWindowPolicy.select(10, "example.app", listOf(host, popup.copy(parent = null, anchor = 10)))
        assertNull(result.reason); assertEquals(11, result.target)
    }
    @Test fun focusedPopupIncludesItsBackgroundHostForSecurityAndIdentity() {
        val result = ControlWindowPolicy.select(11, "example.app", listOf(host.copy(active = false, focused = false),
            popup.copy(active = true, focused = true)))
        assertNull(result.reason); assertEquals(11, result.target); assertEquals(listOf(10, 11), result.members)
    }
    @Test fun packageCoOccurrenceAndForeignCoveringWindowsNeverEstablishOwnership() {
        for (bad in listOf(popup.copy(parent = null), popup.copy(packageName = "foreign.app"),
            popup.copy(parent = 99), popup.copy(hasRoot = false), popup.copy(pictureInPicture = true)))
            assertNotNull(ControlWindowPolicy.select(10, "example.app", listOf(host, bad)).reason)
    }
    @Test fun conflictingOwnersCyclesAndAmbiguousLayersAreRejected() {
        for (bad in listOf(popup.copy(parent = 10, anchor = 99), popup.copy(parent = 11), popup.copy(layer = 3)))
            assertNotNull(ControlWindowPolicy.select(10, "example.app", listOf(host, bad)).reason)
        assertNotNull(ControlWindowPolicy.select(11, "example.app", listOf(host.copy(parent = 11),
            popup.copy(active = true, focused = true))).reason)
    }
    @Test fun keyboardAndOtherDisplaysAreNeverActionSurfaces() {
        val ime = popup.copy(application = false, packageName = "keyboard", parent = null)
        val otherDisplay = popup.copy(id = 12, display = 1, packageName = "foreign.app", parent = null)
        val result = ControlWindowPolicy.select(10, "example.app", listOf(host, ime, otherDisplay))
        assertNull(result.reason); assertEquals(listOf(10), result.members)
        assertNotNull(ControlWindowPolicy.select(11, "example.app", listOf(host.copy(display = 1),
            popup.copy(active = true, focused = true))).reason)
    }
    @Test fun nestedOwnedMenusAreOrderedAndBounded() {
        val second = popup.copy(id = 12, layer = 6, parent = 11)
        val third = popup.copy(id = 13, layer = 7, parent = 12)
        val fourth = popup.copy(id = 14, layer = 8, parent = 13)
        val result = ControlWindowPolicy.select(10, "example.app", listOf(second, host, third, popup))
        assertNull(result.reason); assertEquals(13, result.target); assertEquals(listOf(10, 11, 12, 13), result.members)
        assertEquals("owned_window_limit", ControlWindowPolicy.select(10, "example.app", listOf(host, popup, second, third, fourth)).reason)
    }
    @Test fun duplicateOrUnboundedWindowListsFailClosed() {
        assertNotNull(ControlWindowPolicy.select(10, "example.app", listOf(host, host)).reason)
        assertNotNull(ControlWindowPolicy.select(10, "example.app", (0..64).map { host.copy(id = it) }).reason)
    }
}
