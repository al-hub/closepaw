package ai.closepaw.ui.overlay
import org.junit.Assert.*
import org.junit.Test
class OverlayPositionPolicyTest {
    @Test fun realSizeClampHandlesPersistedExtremeAndFoldedScreen() {
        assertEquals(198 to 432, OverlayPositionPolicy.place(Int.MAX_VALUE, Int.MAX_VALUE, 302,168,500,600,400,40,56))
        assertEquals(0 to 0, OverlayPositionPolicy.place(Int.MIN_VALUE, Int.MIN_VALUE,302,168,500,600,400,400,56))
        assertNull(OverlayPositionPolicy.place(0,0,501,168,500,600,400,40,56))
    }
    @Test fun stopAlwaysWinsRestorationOverlapAndNoRoom() {
        val p = requireNotNull(OverlayPositionPolicy.place(10,10,200,50,400,600,10,10,56))
        assertEquals(10 to 66,p)
        assertNull(OverlayPositionPolicy.place(0,0,100,100,100,100,10,10,56))
    }
    @Test fun actualScreenStopRectangleIncludesInsetWithoutInflatingItsWidth() {
        // X screen y=126 is not its LP y=59; collision uses the observed rectangle.
        assertEquals(43 to 59, OverlayPositionPolicy.place(43, 59, 395, 168, 1440, 3120, 1172, 126, 168))
        // Before first traversal reserve the vertical frame uncertainty, not extra horizontal width.
        assertEquals(180 to 0, OverlayPositionPolicy.place(180, 0, 100, 50, 600, 600, 100, 0, 56, 123))
        assertEquals(100 to 123, OverlayPositionPolicy.place(100, 100, 100, 50, 600, 600, 100, 0, 56, 123))
    }
}
