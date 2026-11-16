package ai.closepaw.chatgpt.control

/** Solve only a uniquely constrained, in-display, unit-scale translation of
 * the entire isolated IME buffer. The touch rectangle must fit the buffer;
 * it is never the buffer origin. Pixel comparison after this step is still
 * mandatory. If more than one position is possible, do not infer one. */
internal object KeyboardDisplayAlignment {
    fun locate(display: TapBounds, width: Int, height: Int, touch: TapBounds): TapBounds? {
        if (!display.valid || display.left != 0 || display.top != 0 ||
            !display.contains(touch) || width !in 1..display.right || height !in 1..display.bottom) return null
        val minX = maxOf(0, touch.right - width)
        val maxX = minOf(touch.left, display.right - width)
        val minY = maxOf(0, touch.bottom - height)
        val maxY = minOf(touch.top, display.bottom - height)
        if (minX != maxX || minY != maxY) return null
        return TapBounds(minX, minY, minX + width, minY + height).takeIf { it.contains(touch) }
    }
}
