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
    @Test fun unknownPixelsAreMaskedAndCanNeverAuthorizeCoordinates() {
        val actual=IntArray(16){7}; actual[6]=99
        val window=TapBounds(1,1,3,3)
        val runs=OwnedPixelPolicy.verifiedRuns(actual,4,IntArray(4){7},window,listOf(window))!!
        assertTrue(OwnedPixelPolicy.contains(runs,TapBounds(1,1,2,2)))
        assertFalse(OwnedPixelPolicy.contains(runs,TapBounds(2,1,3,2)))
        assertFalse(OwnedPixelPolicy.contains(runs,window))
        assertNull(OwnedPixelPolicy.verifiedRuns(actual,4,IntArray(4){7},window,listOf(window),limit=1))
    }

}
