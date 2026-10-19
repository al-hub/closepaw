package ai.closepaw.chatgpt.control

/** Pure screen geometry; no caller-supplied point or partial/clipped target is accepted. */
internal data class TapBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val valid: Boolean get() = left >= 0 && top >= 0 && right > left && bottom > top
    fun contains(x: Int, y: Int) = valid && x >= left && x < right && y >= top && y < bottom
    fun contains(other: TapBounds) = valid && other.valid && other.left >= left && other.top >= top &&
        other.right <= right && other.bottom <= bottom
    val center: Pair<Int, Int> get() = (left + (right.toLong() - left).div(2).toInt()) to
        (top + (bottom.toLong() - top).div(2).toInt())
}

internal object NodeTapPolicy {
    fun point(target: TapBounds, window: TapBounds, covering: List<TapBounds>): Pair<Int, Int>? {
        if (!window.contains(target)) return null
        val (x, y) = target.center
        if (!target.contains(x, y) || covering.any { it.contains(x, y) }) return null
        return x to y
    }
}
