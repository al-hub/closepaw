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

    fun failure(proof: VisualTargetProof, imageRect: TapBounds, node: TapBounds, ime: TapBounds,
        requireWholeNode: Boolean = false): Failure? {
        // map() enforces both geometry and the disclosure mask. Split those
        // independent reasons without ever accepting a failed map().
        val mapped = proof.map(imageRect) ?: return if (proof.mapGeometry(imageRect) == null) {
            Failure.IMAGE_RECT_OUTSIDE_FRAME
        } else {
            Failure.IMAGE_CROP_NOT_DISCLOSED
        }
        if (!node.contains(mapped)) return Failure.IMAGE_RECT_OUTSIDE_NODE
        if (!ime.contains(node)) return Failure.NODE_OUTSIDE_IME
        // A native key is identified by its unique IME node. Its injected patch,
        // not its entire possibly masked caption, must be owned. Image-only
        // candidates retain whole-node disclosure for their OCR identity.
        if (requireWholeNode && !OwnedPixelPolicy.contains(proof.disclosedRegions, node))
            return Failure.NODE_PIXELS_NOT_DISCLOSED
        return null
    }
    /**
     * A physical native key has a separately identified, unique IME node.
     * Find an independently disclosed interior image patch when the original
     * user-reviewed crop is partially masked. This is NOT dispatch permission:
     * the native KeyboardTouchGeometry selector, isolated pixel revalidation,
     * fresh window/IME/editor check and final gesture gate still decide.
     * Image-only (OCR) candidates may not migrate away from their observed glyph.
     */
    fun resolvedCrop(proof: VisualTargetProof, requested: TapBounds, node: TapBounds, ime: TapBounds,
        requireWholeNode: Boolean): TapBounds? {
        if (proof.scope != "keyboard" || proof.sourceDisplayId != 0 ||
            proof.sourceImeWindowId < 0 || !proof.sourceDisclosureAllowed() ||
            !proof.window.contains(node) || !ime.contains(node)) return null
        val originalGeometry = proof.mapGeometry(requested) ?: return null
        // A misplaced or ambiguous initial proposal is not recoverable.
        if (!node.contains(originalGeometry)) return null
        if (proof.map(requested) != null &&
            (!requireWholeNode || OwnedPixelPolicy.contains(proof.disclosedRegions, node))) return requested
        if (requireWholeNode || proof.disclosedRegions.isEmpty()) return null

        val insetX = maxOf(2, (node.right - node.left) / 8)
        val insetY = maxOf(2, (node.bottom - node.top) / 8)
        val inner = TapBounds(node.left + insetX, node.top + insetY,
            node.right - insetX, node.bottom - insetY)
        if (!inner.valid) return null
        val viewW = (proof.window.right - proof.window.left).toLong()
        val viewH = (proof.window.bottom - proof.window.top).toLong()
        if (viewW <= 0 || viewH <= 0 || proof.image.width <= 0 || proof.image.height <= 0) return null
        fun imgX(x: Int) = ((x.toLong() - proof.window.left) * proof.image.width / viewW).toInt()
        fun imgY(y: Int) = ((y.toLong() - proof.window.top) * proof.image.height / viewH).toInt()
        // Image-space dimensions scale with the native key; no OEM, resolution
        // or letter-specific pixel position is encoded.
        val patchW = maxOf(2, (imgX(node.right) - imgX(node.left)) / 6)
        val patchH = maxOf(2, (imgY(node.bottom) - imgY(node.top)) / 6)
        val left = maxOf(0, imgX(node.left) - 2)
        val top = maxOf(0, imgY(node.top) - 2)
        val right = minOf(proof.image.width - patchW, imgX(node.right) + 2)
        val bottom = minOf(proof.image.height - patchH, imgY(node.bottom) + 2)
        if (right < left || bottom < top) return null
        val center = node.center
        var best: TapBounds? = null
        var bestScore = Long.MAX_VALUE
        for (y in top..bottom) for (x in left..right) {
            val candidate = TapBounds(x, y, x + patchW, y + patchH)
            val mapped = proof.map(candidate) ?: continue
            if (!inner.contains(mapped)) continue
            val (cx, cy) = mapped.center
            val dx = cx.toLong() - center.first
            val dy = cy.toLong() - center.second
            // Stay within the user-reviewed crop if an owned interior exists.
            val score = (if (requested.contains(candidate)) 0L else 1_000_000_000L) +
                dx * dx + dy * dy
            if (score < bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

}
