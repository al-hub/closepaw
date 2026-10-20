package ai.closepaw.ui.overlay.compose

import org.junit.Assert.*
import org.junit.Test

class VisualizationTimingTest {
    @Test fun `path reaches endpoint at actual gesture completion before fading`() {
        assertEquals(0.5f, VisualizationTiming.motionProgress(250, 500), 0.001f)
        assertEquals(1f, VisualizationTiming.motionProgress(500, 500), 0.001f)
        assertEquals(1f, VisualizationTiming.motionProgress(700, 500), 0.001f)
        assertEquals(1f, VisualizationTiming.opacity(500, 500, 400), 0.001f)
        assertEquals(0.5f, VisualizationTiming.opacity(700, 500, 400), 0.001f)
        assertEquals(0f, VisualizationTiming.opacity(900, 500, 400), 0.001f)
    }
    @Test fun `negative elapsed and zero duration remain bounded`() {
        assertEquals(0f, VisualizationTiming.motionProgress(-10, 500), 0f)
        assertEquals(1f, VisualizationTiming.motionProgress(1, 0), 0f)
        assertEquals(1f, VisualizationTiming.opacity(-10, 0, 0), 0f)
        assertEquals(0f, VisualizationTiming.opacity(2, 0, 0), 0f)
    }
}
