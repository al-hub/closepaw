package ai.closepaw.chatgpt.control

import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import org.junit.Assert.*
import org.junit.Test

/**
 * #147: reproduce the Galaxy 1440x3120 screen vs 443x960 delivered-image
 * boundary. This fixture contains only geometry; no screenshot or editor text.
 * Actual OEM ownership rectangles and submitted image_rect remain unobserved.
 */
class KeyboardSequencePixelProofTest {
    private val ime = TapBounds(0, 1798, 1440, 3120)
    private val owned = listOf(ime)
    private fun proof(regions: List<TapBounds> = owned) = VisualTargetProof(
        ScreenImage(443, 960, "image/jpeg", byteArrayOf(1), ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
        "synthetic", TapBounds(0, 0, 1440, 3120), "synthetic",
        System.currentTimeMillis(), scope = "keyboard", sourceDisplayId = 0,
        sourceImeWindowId = 4, imeBounds = "0 1798 1440 3120",
        disclosedRegions = regions
    )

    @Test fun allSixDeviceGeometryCandidatesPassWhenImageRectsAndOwnedPixelsMatch() {
        // Node dimensions from the reported Galaxy 6-key test; rectangles are
        // independently chosen *image-space* interiors, not screen coordinates.
        val keys = listOf(
            TapBounds(451, 2154, 569, 2309) to TapBounds(145, 675, 168, 700), // ㄱ
            TapBounds(1081, 2354, 1199, 2509) to TapBounds(342, 733, 360, 762), // ㅏ
            TapBounds(503, 2754, 1059, 2909) to TapBounds(190, 855, 280, 884), // Space
            TapBounds(241, 2354, 359, 2509) to TapBounds(80, 737, 104, 760), // ㄴ
        )
        for (index in listOf(0, 1, 2, 3, 1, 2)) {
            val (node, imageRect) = keys[index]
            assertTrue(node.contains(proof().map(imageRect)!!))
            assertNull(KeyboardSequencePixelProof.failure(proof(), imageRect, node, ime))
        }
    }

    @Test fun passingScreenSpaceNodeCoordinatesAsImageRectBlocksBeforeTouch() {
        val node = TapBounds(451, 2154, 569, 2309)
        assertNull(proof().map(node))
        assertEquals(KeyboardSequencePixelProof.Failure.IMAGE_RECT_OUTSIDE_FRAME,
            KeyboardSequencePixelProof.failure(proof(), node, node, ime))
    }

    @Test fun maskedInteriorAndMaskedNodeBoundaryAreDifferentFailures() {
        val node = TapBounds(451, 2154, 569, 2309)
        val crop = TapBounds(145, 675, 168, 700)
        val cropMasked = proof(listOf(TapBounds(0, 1798, 470, 3120)))
        assertNull(cropMasked.map(crop))
        assertEquals(KeyboardSequencePixelProof.Failure.IMAGE_CROP_NOT_DISCLOSED,
            KeyboardSequencePixelProof.failure(cropMasked, crop, node, ime))

        // Crop is independently owned, but not all of the physical key node.
        val edgeMasked = proof(listOf(TapBounds(460, 2154, 560, 2309)))
        assertNotNull(edgeMasked.map(crop))
        assertFalse(OwnedPixelPolicy.contains(edgeMasked.disclosedRegions, node))
        assertEquals(KeyboardSequencePixelProof.Failure.NODE_PIXELS_NOT_DISCLOSED,
            KeyboardSequencePixelProof.failure(edgeMasked, crop, node, ime))
    }

    @Test fun misregisteredImageToNodeAndImeGeometryAreSeparatelyRejected() {
        val node = TapBounds(451, 2154, 569, 2309)
        val wrongCrop = TapBounds(230, 675, 250, 700)
        assertEquals(KeyboardSequencePixelProof.Failure.IMAGE_RECT_OUTSIDE_NODE,
            KeyboardSequencePixelProof.failure(proof(), wrongCrop, node, ime))
        assertEquals(KeyboardSequencePixelProof.Failure.NODE_OUTSIDE_IME,
            KeyboardSequencePixelProof.failure(proof(), TapBounds(145, 675, 168, 700), node,
                TapBounds(0, 1798, 1440, 2220)))
    }
    @Test fun ownedPartialNativeKeyCanUseCertifiedInteriorWithoutFullNodeDisclosure() {
        // Reproduces the right-edge native key boundary from Galaxy v0.1.140,
        // with the user-reviewed crop wholly inside an independently owned area.
        val key = TapBounds(1221, 2354, 1339, 2509)
        val crop = TapBounds(382, 741, 393, 755)
        val partlyOwned = proof(listOf(TapBounds(1221, 2354, 1280, 2509)))
        assertNotNull(partlyOwned.map(crop))
        assertFalse(OwnedPixelPolicy.contains(partlyOwned.disclosedRegions, key))
        assertNull(KeyboardSequencePixelProof.failure(partlyOwned, crop, key, ime))
    }

}
