package ai.closepaw.ui.overlay

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-JVM tests for IslandWindowSizePolicy verifying fixed compact bounds across status,
 * QA stage, error badge, density, fontScale, orientation, and emergency-X clearance.
 */
class IslandWindowSizePolicyTest {
    @Test fun compactBoundsAreFixedAcrossStatusAndQaStageUpdates() {
        val base = IslandWindowSizePolicy.resolve(
            screenWidthPx = 1080, screenHeightPx = 2400, density = 2.625f,
            fontScale = 1.0f, stopWidthPx = 147, isDebug = true, expanded = false,
        )
        assertFalse(base.wrapContent)
        assertTrue("width must fit comfortably on Galaxy", base.widthPx in 500..700)
        assertTrue("height must meet 48dp minimum touch target", base.heightPx >= 126)

        // Repeated resolutions with identical display geometry (representing rapid status/stage/badge updates)
        // produce bit-identical width & height -> zero WindowManager bounds relayout churn.
        repeat(10) {
            val next = IslandWindowSizePolicy.resolve(
                screenWidthPx = 1080, screenHeightPx = 2400, density = 2.625f,
                fontScale = 1.0f, stopWidthPx = 147, isDebug = true, expanded = false,
            )
            assertEquals(base, next)
        }
    }

    @Test fun compactWidthClearsEmergencyStopAtPersistedTopCoordinates43x59AcrossScreenSizes() {
        data class DisplayCase(val w: Int, val h: Int, val density: Float, val isDebug: Boolean)
        val cases = listOf(
            DisplayCase(1080, 2400, 2.625f, true),  // Android 35 emulator / Galaxy portrait
            DisplayCase(1080, 2400, 3.0f, true),    // 360dp portrait
            DisplayCase(720, 1600, 2.0f, true),     // 360dp xhdpi portrait
            DisplayCase(480, 800, 1.5f, true),      // Narrow 320dp screen
            DisplayCase(2400, 1080, 2.625f, true),  // Landscape
            DisplayCase(1080, 2400, 2.625f, false), // Release build
        )
        for (c in cases) {
            val stopX = c.w - (68 * c.density).toInt()
            val stopY = (40 * c.density).toInt()
            val stopSize = (56 * c.density).toInt()
            val stopHeight = stopSize + (24 * c.density).toInt()
            val size = IslandWindowSizePolicy.resolve(
                screenWidthPx = c.w, screenHeightPx = c.h, density = c.density,
                fontScale = 1.0f, stopWidthPx = stopSize, isDebug = c.isDebug, expanded = false,
            )
            assertFalse("compact size must not wrap content for $c", size.wrapContent)
            val placed = OverlayPositionPolicy.place(
                x = 43, y = 59, width = size.widthPx, height = size.heightPx,
                screenWidth = c.w, screenHeight = c.h,
                stopX = stopX, stopY = stopY, stopSize = stopSize, stopHeight = stopHeight,
            )
            assertNotNull("must find safe placement for $c", placed)
            assertEquals("IslandScreenCoordinatesTest expects x=43 preserved for $c", 43, placed!!.first)
            assertEquals("IslandScreenCoordinatesTest expects y=59 preserved for $c", 59, placed.second)
            assertTrue("must not intersect emergency stop X for $c", placed.first + size.widthPx <= stopX)
            assertTrue("must stay within screen width for $c", placed.first + size.widthPx <= c.w)
            assertTrue("must stay within screen height for $c", placed.second + size.heightPx <= c.h)
        }
    }

    @Test fun fontScaleScalesCompactHeightSafelyWithoutOccludingImeOrApp() {
        val h10 = IslandWindowSizePolicy.resolve(1080, 2400, 3.0f, fontScale = 1.0f, isDebug = true)
        val h15 = IslandWindowSizePolicy.resolve(1080, 2400, 3.0f, fontScale = 1.5f, isDebug = true)
        val h20 = IslandWindowSizePolicy.resolve(1080, 2400, 3.0f, fontScale = 2.0f, isDebug = true)
        val h25 = IslandWindowSizePolicy.resolve(1080, 2400, 3.0f, fontScale = 2.5f, isDebug = true)

        assertEquals(144, h10.heightPx) // 48dp * 3.0
        assertEquals(144, h15.heightPx) // max(48, 40) = 48dp
        assertEquals(144, h20.heightPx) // max(48, 48) = 48dp
        assertTrue("2.5x fontScale grows cleanly above 48dp", h25.heightPx > h20.heightPx)
        assertTrue("height remains capped <= 96dp to avoid IME/app occlusion", h25.heightPx <= 288)
        assertEquals("width remains fixed across fontScale", h10.widthPx, h25.widthPx)
    }

    @Test fun explicitExpandedModeUsesWrapContentAndInvalidDisplayFailsClosed() {
        val expanded = IslandWindowSizePolicy.resolve(1080, 2400, 2.625f, isDebug = true, expanded = true)
        assertTrue(expanded.wrapContent)
        assertTrue(IslandWindowSizePolicy.resolve(0, 2400, 2.625f).wrapContent)
        assertTrue(IslandWindowSizePolicy.resolve(1080, 0, 2.625f).wrapContent)
        assertTrue(IslandWindowSizePolicy.resolve(1080, 2400, 0f).wrapContent)
    }

    @Test fun narrowMultiWindowFallsBackToWrapContentInsteadOfViolatingEmergencyStopClearance() {
        // Exact narrow-screen counterexample: 420px @ 2.0x (210dp), minWidth=280px,
        // stopClearance>=144px, leftAllowance=48px -> safeCompactWidth=228px < 280px.
        // Must NOT force a 280px fixed width that collides with emergency X!
        val narrow210dp = IslandWindowSizePolicy.resolve(
            screenWidthPx = 420, screenHeightPx = 1600, density = 2.0f,
            fontScale = 1.0f, stopWidthPx = 112, isDebug = true, expanded = false,
        )
        assertTrue("210dp narrow multi-window must fall back to wrapContent", narrow210dp.wrapContent)

        // Even with minimal stopWidth=68px (stopClearance=max(100,144)=144px, or 100px):
        val narrow150dp = IslandWindowSizePolicy.resolve(
            screenWidthPx = 300, screenHeightPx = 1600, density = 2.0f,
            fontScale = 1.0f, stopWidthPx = 68, isDebug = true, expanded = false,
        )
        assertTrue("150dp narrow split-screen must fall back to wrapContent", narrow150dp.wrapContent)
    }
}
