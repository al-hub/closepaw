package ai.closepaw.chatgpt.control

import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import org.junit.Assert.*
import org.junit.Test

/**
 * #276: offline-only regression of the actual v0.1.137 Galaxy preflight boundary.
 * Values are non-sensitive metadata from issue #147, not a live capture.
 * These checks do not authorize physical input or establish an OEM PASS.
 */
class KeyboardPreflightRegressionTest {
    private val editor = ControlNode(
        id = 137, resourceId = "com.android.chrome:id/url_bar",
        sourcePackage = "com.android.chrome", sourceWindowId = 8464,
        bounds = "224 127 1076 323", className = "android.widget.EditText",
        enabled = true, editable = true, focusable = true, focused = true,
        text = "", empty = true, selectionStart = -1, selectionEnd = -1
    )

    @Test fun emptyChromeUrlBarWithRawMinusOneSelectionStillNeedsNativeProof() {
        assertTrue(KeyboardCursorProof.needsProof(editor))
        assertFalse(KeyboardCursorProof.provesEmpty(0, -1, -1, 0))
        assertFalse(KeyboardCursorProof.provesEmpty(0, 0, 0, -1))
        assertTrue(KeyboardCursorProof.provesEmpty(0, 0, 0, 0))
        assertEquals("keyboard_input_target_changed", KeyboardTargetPolicy.refusal(
            editor.copy(selectionStart = 0, selectionEnd = 0),
            editor
        ))
    }

    @Test fun popupWindowAndInputFocusLossCannotBeTreatedAsStableKeyboardTarget() {
        val before = ControlFrame(
            packageName = "com.android.chrome", windowId = 8464, fingerprint = "before",
            nodes = listOf(editor), foregroundWindowId = 8464, touchContext = "stable"
        )
        val popup = before.copy(windowId = 8492, fingerprint = "after_popup")
        assertEquals("screen_changed", GenericControlActionPolicy.refusal(before, popup, "visual_key"))
        assertEquals("keyboard_input_target_changed", KeyboardTargetPolicy.refusal(editor, editor.copy(focused = false)))
        assertEquals("keyboard_input_target_changed", KeyboardTargetPolicy.refusal(editor, null))
    }

    private fun proof(regions: List<TapBounds>) = VisualTargetProof(
        ScreenImage(443, 960, "image/jpeg", byteArrayOf(1), ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
        "sanitized-metadata", TapBounds(0, 0, 1440, 3120), "synthetic",
        System.currentTimeMillis(), scope = "keyboard", sourceDisplayId = 0,
        sourceImeWindowId = 8411, imeBounds = "0 1798 1440 3120",
        disclosedRegions = regions
    )

    @Test fun keyboardImageReportsItsActualTenSecondSequenceProofWindow() {
        val p = proof(listOf(TapBounds(0, 1798, 1440, 3120)))
        // Even accessibility screenshots have a 10s action gate in visualKeySequence.
        assertEquals("10000", p.metadata().getValue("expires_in_ms").toString())
    }

    @Test fun screenSpaceNodeBoundsCannotBePassedAsImageSpaceRect() {
        val node = TapBounds(451, 2154, 569, 2309)
        val p = proof(listOf(TapBounds(0, 1798, 1440, 3120)))
        assertEquals(KeyboardSequencePixelProof.Failure.IMAGE_RECT_OUTSIDE_FRAME,
            KeyboardSequencePixelProof.failure(p, node, node, TapBounds(0, 1798, 1440, 3120)))
    }

    @Test fun partialNativeCropDoesNotAuthorizeImageOnlyKeyIdentity() {
        val node = TapBounds(451, 2154, 569, 2309)
        val crop = TapBounds(145, 675, 168, 700)
        val partial = proof(listOf(TapBounds(460, 2154, 560, 2309)))
        // The uniquely identified native node may use an owned patch, but a
        // key inferred from image pixels still requires full disclosure.
        assertNull(KeyboardSequencePixelProof.failure(
            partial, crop, node, TapBounds(0, 1798, 1440, 3120)))
        assertEquals(KeyboardSequencePixelProof.Failure.NODE_PIXELS_NOT_DISCLOSED,
            KeyboardSequencePixelProof.failure(
                partial, crop, node, TapBounds(0, 1798, 1440, 3120), requireWholeNode = true))
        val full = proof(listOf(TapBounds(0, 1798, 1440, 3120)))
        assertNull(KeyboardSequencePixelProof.failure(full, crop, node, TapBounds(0, 1798, 1440, 3120)))
    }
}
