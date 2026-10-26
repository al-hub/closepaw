package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class DisplayCapturePolicyTest {
    @Test fun onlyCompleteOwnedDefaultDisplaySurfaceIsAdmitted() {
        assertNull(DisplayCapturePolicy.refusal(true,0,0,listOf(1,2),setOf(1,2)))
        assertEquals("display_unowned_window",DisplayCapturePolicy.refusal(true,0,0,listOf(1,2,3),setOf(1,2)))
        assertEquals("display_geometry_unverified",DisplayCapturePolicy.refusal(true,1,0,listOf(1),setOf(1)))
        assertEquals("display_geometry_unverified",DisplayCapturePolicy.refusal(false,0,0,listOf(1),setOf(1)))
        assertEquals("display_unowned_window",DisplayCapturePolicy.refusal(true,0,0,listOf(1,1),setOf(1)))
    }
    @Test fun diagnosticsExposeOnlyBoundedNativeCodes() {
        assertEquals("screenshot_secure_window",VisualCaptureFailure.native(6))
        assertEquals("screenshot_invalid_window",VisualCaptureFailure.native(5))
        assertEquals("screenshot_native_failure",VisualCaptureFailure.native(8123))
    }
    @Test fun displayProofPreservesWholePixelCoordinateOriginAndRevocation() {
        val p=VisualTargetProof(ai.closepaw.model.ScreenImage(432,960,"image/jpeg",byteArrayOf(1),ai.closepaw.model.ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
            "hash",TapBounds(0,0,1080,2400),"rotation0",System.currentTimeMillis(),scope="display",sourceDisplayId=0,rotation=0,disclosedRegions=listOf(TapBounds(0,0,1080,2400)))
        assertEquals(TapBounds(100,2000,200,2100),p.map(TapBounds(40,800,80,840)))
        assertNull(p.map(TapBounds(0,0,433,960)))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(0),p.metadata()["source_display_id"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(1080),p.metadata()["source_width"])
    }
    @Test fun transientWindowCannotBeHiddenByMatchingEndpointSnapshots() {
        val signal=ControlChangeSignal(); val before=signal.version()
        assertNull(DisplayCapturePolicy.interveningChange(before,signal.version()))
        signal.changed(); signal.changed() // overlay appeared then disappeared
        assertEquals("display_window_changed_during_capture",DisplayCapturePolicy.interveningChange(before,signal.version()))
    }
    @Test fun displayKeyKeepsSeparateImeProvenanceAtNativeDispatch() {
        val k=ControlKeyboard(6,"ime","ime/.Keyboard")
        val p=VisualTargetProof(ai.closepaw.model.ScreenImage(432,960,"image/jpeg",byteArrayOf(1),ai.closepaw.model.ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
            "hash",TapBounds(0,0,1080,2400),"rotation0",System.currentTimeMillis(),scope="display",sourceDisplayId=0,
            sourceImeWindowId=6,imeBounds="0 1600 1080 2400",displayGeneration=0)
        assertNull(VisualKeyboardPolicy.proofOwnerRefusal(p,k))
        assertNotNull(VisualKeyboardPolicy.proofOwnerRefusal(p,k.copy(windowId=7)))
        assertNotNull(VisualKeyboardPolicy.proofOwnerRefusal(p.copy(sourceDisplayId=1),k))
        assertNotNull(VisualKeyboardPolicy.proofOwnerRefusal(p.copy(displayGeneration=-1),k))
        assertNotNull(VisualKeyboardPolicy.proofOwnerRefusal(p.copy(scope="app"),k))
        assertNull(VisualKeyboardPolicy.proofOwnerRefusal(p.copy(scope="keyboard",sourceWindowId=6),k))
    }

}
