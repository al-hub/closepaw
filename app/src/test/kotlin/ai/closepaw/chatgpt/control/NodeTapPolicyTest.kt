package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class NodeTapPolicyTest {
    private val window = TapBounds(0, 0, 1080, 2400)
    private val button = TapBounds(80, 120, 440, 220)
    @Test fun observedButtonCenterIsDerivedWithoutCallerCoordinates() {
        assertEquals(260 to 170, NodeTapPolicy.point(button, window, emptyList()))
    }
    @Test fun clippingEmptyInvertedOrNegativeBoundsCannotTouchAnotherSurface() {
        for (bad in listOf(TapBounds(-1, 120, 440, 220), TapBounds(80, -1, 440, 220),
            TapBounds(80, 120, 1081, 220), TapBounds(80, 120, 440, 2401),
            TapBounds(80, 120, 80, 220), TapBounds(440, 120, 80, 220)))
            assertNull(NodeTapPolicy.point(bad, window, emptyList()))
    }
    @Test fun aCoveringImeOrOverlayBlocksThePoint() {
        assertNull(NodeTapPolicy.point(button, window, listOf(TapBounds(0, 100, 1080, 1000))))
        assertNull(NodeTapPolicy.point(button, window, listOf(TapBounds(259, 169, 261, 171))))
    }
    @Test fun aWindowAwayFromTheButtonDoesNotBlockIt() {
        assertEquals(260 to 170, NodeTapPolicy.point(button, window, listOf(TapBounds(0, 1500, 1080, 2400))))
    }
    @Test fun exactEdgesAndOnePixelTargetsAreWellDefined() {
        assertEquals(0 to 0, NodeTapPolicy.point(TapBounds(0, 0, 1, 1), window, emptyList()))
        assertEquals(1079 to 2399, NodeTapPolicy.point(TapBounds(1079, 2399, 1080, 2400), window, emptyList()))
        assertEquals(260 to 170, NodeTapPolicy.point(button, window, listOf(TapBounds(0, 0, 260, 170))))
    }
    @Test fun missingOrInvalidWindowGeometryIsRejected() {
        assertNull(NodeTapPolicy.point(button, TapBounds(0, 0, 0, 0), emptyList()))
        assertNull(NodeTapPolicy.point(button, TapBounds(-10, 0, 1080, 2400), emptyList()))
    }
}
