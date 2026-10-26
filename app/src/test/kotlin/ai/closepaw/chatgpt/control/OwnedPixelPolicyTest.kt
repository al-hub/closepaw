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

    @Test fun identicalRowsCompactWithoutExpandingVerifiedCoverage() {
        val window=TapBounds(0,0,4,100)
        val actual=IntArray(400){7}
        val isolated=IntArray(400){7}
        val rects=OwnedPixelPolicy.verifiedRuns(actual,4,isolated,window,listOf(window),limit=1)
        assertEquals(listOf(window),rects)
        assertTrue(OwnedPixelPolicy.contains(rects!!,TapBounds(0,10,4,90)))
        actual[50*4+2]=99
        val gap=OwnedPixelPolicy.verifiedRuns(actual,4,isolated,window,listOf(window))!!
        assertFalse(OwnedPixelPolicy.contains(gap,TapBounds(2,50,3,51)))
        assertFalse(OwnedPixelPolicy.contains(gap,window))
        assertNull(OwnedPixelPolicy.verifiedRuns(actual,4,isolated,window,listOf(window),limit=1))
    }
    @Test fun separateRegionsDoNotAccidentallyBridgeUnknownPixels() {
        val window=TapBounds(0,0,4,4)
        val actual=IntArray(16){7}
        val isolated=IntArray(16){7}
        val regions=listOf(TapBounds(0,0,4,1),TapBounds(0,3,4,4))
        val result=OwnedPixelPolicy.verifiedRuns(actual,4,isolated,window,regions)!!
        assertEquals(2,result.size)
        assertFalse(OwnedPixelPolicy.contains(result,TapBounds(0,1,4,3)))
        assertTrue(OwnedPixelPolicy.contains(result,regions.first()))
    }

}
