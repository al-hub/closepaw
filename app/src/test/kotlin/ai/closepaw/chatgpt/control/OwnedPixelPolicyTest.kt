package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class OwnedPixelPolicyTest {
    @Test fun unreportedStaticOrTransientOverlayCannotLeakThroughOwnedRegion() {
        val display=IntArray(16){7}; val isolated=IntArray(4){7}
        val window=TapBounds(1,1,3,3); val rows=listOf(window)
        assertTrue(OwnedPixelPolicy.matches(display,4,isolated,window,rows))
        display[6]=99 // another app's pixel: no event/window-list assumption
        assertFalse(OwnedPixelPolicy.matches(display,4,isolated,window,rows))
    }
    @Test fun offsetsMaskingAndAmbiguousBuffersFailClosed() {
        val display=IntArray(16){7};display[0]=99
        assertTrue(OwnedPixelPolicy.matches(display,4,IntArray(4){7},TapBounds(1,1,3,3),listOf(TapBounds(1,1,3,3))))
        assertFalse(OwnedPixelPolicy.matches(display,4,IntArray(3),TapBounds(1,1,3,3),listOf(TapBounds(1,1,3,3))))
        assertFalse(OwnedPixelPolicy.matches(display,4,IntArray(4){7},TapBounds(1,1,3,3),listOf(TapBounds(0,0,2,2))))
        assertFalse(OwnedPixelPolicy.matches(display,4,IntArray(4){7},TapBounds(1,1,3,3),emptyList()))
    }
}
