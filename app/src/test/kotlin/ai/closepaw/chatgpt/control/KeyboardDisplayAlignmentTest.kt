package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class KeyboardDisplayAlignmentTest {
    private val opaque = 0xff000000.toInt()
    private fun pattern(width: Int, height: Int) = IntArray(width * height) { opaque or (it + 30) }
    private fun put(display: IntArray, dw: Int, ime: IntArray, iw: Int, x: Int, y: Int) {
        for (row in 0 until ime.size / iw) ime.copyInto(display, (y + row) * dw + x, row * iw, (row + 1) * iw)
    }

    @Test fun defaultDisplayFindsTranslatedImeAndDisclosesOnlyMatchingTouchablePixels() {
        val display = IntArray(100 * 200) { opaque }
        val ime = pattern(100, 173)
        put(display, 100, ime, 100, 0, 27)
        val touch = TapBounds(0, 110, 100, 200)
        val visible = listOf(TapBounds(1, 111, 99, 199))
        val frame = KeyboardDisplayAlignment.locate(TapBounds(0,0,100,200),100,173,touch)
        assertEquals(TapBounds(0, 27, 100, 200), frame)
        // A one-pixel covering surface is never returned.
        display[111 * 100 + 1] = opaque
        display[111 * 100 + 2] = 0
        ime[(111 - 27) * 100 + 2] = 0
        val verified = OwnedPixelPolicy.verifiedRegions(display,100,ime,requireNotNull(frame),visible,opaqueOnly=true)!!
        assertFalse(OwnedPixelPolicy.contains(verified, TapBounds(1, 111, 2, 112)))
        assertFalse(OwnedPixelPolicy.contains(verified, TapBounds(2, 111, 3, 112)))
        assertFalse(OwnedPixelPolicy.contains(verified, TapBounds(1, 28, 2, 29)))
        assertTrue(OwnedPixelPolicy.contains(verified, TapBounds(22, 130, 27, 135)))
    }

    @Test fun realScreenShapesDoNotHardcodeOrigins() {
        val display = TapBounds(0,0,1440,3120)
        val touch = TapBounds(0,1798,1440,3120)
        assertEquals(TapBounds(0,127,1440,3120),KeyboardDisplayAlignment.locate(display,1440,2993,touch))
        assertEquals(TapBounds(0,39,1440,3120),KeyboardDisplayAlignment.locate(display,1440,3081,touch))
    }

    @Test fun ambiguousOversizedAndOutOfRegionInputsFailClosed() {
        val display = TapBounds(0,0,100,100)
        val touch = TapBounds(30, 30, 60, 60)
        assertNull(KeyboardDisplayAlignment.locate(display,80,80,touch))
        assertNull(KeyboardDisplayAlignment.locate(display,101,100,touch))
        assertNull(KeyboardDisplayAlignment.locate(display,100,100,TapBounds(0,0,101,100)))
        assertNull(KeyboardDisplayAlignment.locate(display,29,29,touch))
    }

    @Test fun translatedFrameCannotBeUsedWithoutIndependentMatchingPixels() {
        val screen = TapBounds(0,0,60,100)
        val touch = TapBounds(0,60,60,100)
        val frame = requireNotNull(KeyboardDisplayAlignment.locate(screen,60,90,touch))
        val source = pattern(60,90)
        val otherDisplay = IntArray(60 * 100) { opaque }
        assertTrue(OwnedPixelPolicy.verifiedRegions(otherDisplay,60,source,frame,listOf(touch),opaqueOnly=true)!!.isEmpty())
    }
}
