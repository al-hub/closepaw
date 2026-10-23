package ai.closepaw.ui.overlay

/** Full measured rectangle, overflow-safe. Stop rectangle always has priority. */
internal object OverlayPositionPolicy {
    fun place(x: Int, y: Int, width: Int, height: Int, screenWidth: Int, screenHeight: Int,
        stopX: Int, stopY: Int, stopSize: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0 || width > screenWidth || height > screenHeight) return null
        val maxX = screenWidth - width; val maxY = screenHeight - height
        val px = x.coerceIn(0, maxX); val py = y.coerceIn(0, maxY)
        fun free(a: Int, c: Int) = a.toLong() + width <= stopX || a >= stopX.toLong() + stopSize ||
            c.toLong() + height <= stopY || c >= stopY.toLong() + stopSize
        if (free(px, py)) return px to py
        // Deterministic nearest safe edge; never place the island over emergency X.
        return listOf(px to (stopY.toLong() + stopSize).coerceIn(0, maxY.toLong()).toInt(),
            px to (stopY.toLong() - height).coerceIn(0, maxY.toLong()).toInt(),
            (stopX.toLong() - width).coerceIn(0, maxX.toLong()).toInt() to py,
            (stopX.toLong() + stopSize).coerceIn(0, maxX.toLong()).toInt() to py)
            .filter { free(it.first, it.second) }
            .minByOrNull { kotlin.math.abs(it.first.toLong() - px) + kotlin.math.abs(it.second.toLong() - py) }
    }
}
