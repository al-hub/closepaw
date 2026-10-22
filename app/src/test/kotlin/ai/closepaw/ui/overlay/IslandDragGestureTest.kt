package ai.closepaw.ui.overlay

import org.junit.Assert.*
import org.junit.Test

class IslandDragGestureTest {
    @Test fun longHoldUsesRawScreenDeltasAndOnlyOneHaptic() {
        val g = IslandDragGesture()
        g.down(100f, 200f, 0L)
        assertNull(g.move(110f, 210f, 449L))
        val first = requireNotNull(g.move(120f, 230f, 450L))
        assertEquals(20f, first.dx, 0f); assertEquals(30f, first.dy, 0f); assertTrue(first.began)
        val next = requireNotNull(g.move(125f, 240f, 460L))
        assertEquals(5f, next.dx, 0f); assertEquals(10f, next.dy, 0f); assertFalse(next.began)
    }
    @Test fun cancelledAndMultiplePointersNeverContinueDragging() {
        val g = IslandDragGesture()
        g.down(1f, 2f, 0L); g.cancel()
        assertNull(g.move(99f, 99f, 500L))
        g.down(1f, 2f, 0L); g.move(9f, 9f, 500L, pointers = 2)
        assertNull(g.move(99f, 99f, 600L))
    }
    @Test fun shortTapAndBackwardsTimeNeverMove() {
        val g = IslandDragGesture()
        g.down(1f, 2f, 1000L)
        assertNull(g.move(9f, 9f, 999L)); g.cancel()
        assertNull(g.move(9f, 9f, 1500L))
    }
}
