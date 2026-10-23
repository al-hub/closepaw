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
}
