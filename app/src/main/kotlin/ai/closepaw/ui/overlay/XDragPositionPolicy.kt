package ai.closepaw.ui.overlay

/** Pure drag math shared with XStopOverlayHost; screen coordinates, no app content. */
internal object XDragPositionPolicy {
    data class Position(val x: Int, val y: Int)

    fun position(
        startX: Int,
        startY: Int,
        deltaX: Float,
        deltaY: Float,
        screenWidth: Int,
        screenHeight: Int,
        handleSize: Int,
        topInset: Int,
        bottomInset: Int,
    ): Position {
        val maxX = (screenWidth - handleSize).coerceAtLeast(0)
        val minY = topInset.coerceAtLeast(0)
        val maxY = (screenHeight - handleSize - bottomInset).coerceAtLeast(minY)
        return Position(
            (startX + deltaX.toInt()).coerceIn(0, maxX),
            (startY + deltaY.toInt()).coerceIn(minY, maxY),
        )
    }
}
