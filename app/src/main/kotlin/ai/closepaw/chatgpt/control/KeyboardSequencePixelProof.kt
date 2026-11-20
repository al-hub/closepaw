package ai.closepaw.chatgpt.control

/**
 * Read-only explanation of the exact existing sequence pixel gates.
 * This never grants a touch, changes regions, or retains image contents.
 * The caller must still apply its original gates before dispatch.
 */
internal object KeyboardSequencePixelProof {
    enum class Failure(val code: String) {
        IMAGE_RECT_OUTSIDE_FRAME("image_rect_outside_frame"),
        IMAGE_CROP_NOT_DISCLOSED("image_crop_not_disclosed"),
        IMAGE_RECT_OUTSIDE_NODE("image_rect_outside_node"),
        NODE_OUTSIDE_IME("node_outside_ime"),
        NODE_PIXELS_NOT_DISCLOSED("node_pixels_not_disclosed"),
    }

    fun failure(proof: VisualTargetProof, imageRect: TapBounds, node: TapBounds, ime: TapBounds): Failure? {
        // map() enforces both geometry and the disclosure mask. Split those
        // independent reasons without ever accepting a failed map().
        val mapped = proof.map(imageRect) ?: return if (proof.mapGeometry(imageRect) == null) {
            Failure.IMAGE_RECT_OUTSIDE_FRAME
        } else {
            Failure.IMAGE_CROP_NOT_DISCLOSED
        }
        if (!node.contains(mapped)) return Failure.IMAGE_RECT_OUTSIDE_NODE
        if (!ime.contains(node)) return Failure.NODE_OUTSIDE_IME
        if (!OwnedPixelPolicy.contains(proof.disclosedRegions, node))
            return Failure.NODE_PIXELS_NOT_DISCLOSED
        return null
    }
}
