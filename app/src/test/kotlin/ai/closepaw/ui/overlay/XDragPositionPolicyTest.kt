package ai.closepaw.ui.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XDragPositionPolicyTest {
    @Test fun longDragMovesToExpectedPosition() {
        val p = XDragPositionPolicy.position(100, 150, 210f, 300f, 1080, 2400, 120, 30, 30)
        assertThat(p).isEqualTo(XDragPositionPolicy.Position(310, 450))
    }

    @Test fun clampsAllEdgesIncludingRotationAndNarrowDisplays() {
        for (size in listOf(350 to 800, 800 to 350, 120 to 120)) {
            val right = XDragPositionPolicy.position(0, 0, 9000f, 9000f, size.first, size.second, 100, 24, 24)
            val left = XDragPositionPolicy.position(500, 500, -9000f, -9000f, size.first, size.second, 100, 24, 24)
            assertThat(right.x).isEqualTo((size.first - 100).coerceAtLeast(0))
            assertThat(right.y).isEqualTo((size.second - 100 - 24).coerceAtLeast(24))
            assertThat(left).isEqualTo(XDragPositionPolicy.Position(0, 24))
        }
    }

    @Test fun tenIndependentDragCyclesPersistCalculatedCoordinates() {
        var saved = XDragPositionPolicy.Position(700, 100)
        repeat(10) {
            saved = XDragPositionPolicy.position(
                saved.x, saved.y, -35f, 40f, 1080, 2400, 112, 24, 24,
            )
            val reopened = XDragPositionPolicy.position(
                saved.x, saved.y, 0f, 0f, 1080, 2400, 112, 24, 24,
            )
            assertThat(reopened).isEqualTo(saved)
        }
        assertThat(saved).isEqualTo(XDragPositionPolicy.Position(350, 500))
    }
}
