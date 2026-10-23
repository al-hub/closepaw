package ai.closepaw.ui.overlay

/** Pure validation of an island drag request, not proof that Android moved an overlay. */
data class IslandDragPlan(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val holdMs: Long,
    val moveMs: Long,
) {
    companion object {
        fun create(
            startX: Int, startY: Int, endX: Int, endY: Int,
            holdMs: Long, moveMs: Long,
            displayWidth: Int = Int.MAX_VALUE, displayHeight: Int = Int.MAX_VALUE,
        ): IslandDragPlan? {
            if (displayWidth <= 0 || displayHeight <= 0) return null
            if (holdMs !in 450L..2000L || moveMs !in 100L..5000L) return null
            if (startX !in 0 until displayWidth || endX !in 0 until displayWidth) return null
            if (startY !in 0 until displayHeight || endY !in 0 until displayHeight) return null
            // Keep the stroke below the 5s AccessibilityGestureInjector timeout, with margin.
            if (holdMs + moveMs > 4500L) return null
            val dx = kotlin.math.abs(endX.toLong() - startX.toLong())
            val dy = kotlin.math.abs(endY.toLong() - startY.toLong())
            if (dx < 8L && dy < 8L) return null
            return IslandDragPlan(startX, startY, endX, endY, holdMs, moveMs)
        }
    }
}
