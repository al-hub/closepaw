package ai.closepaw.ui.overlay

/** Motion classification uses raw screen coordinates, independent of a moving window's local origin. */
internal class IslandDragGesture(private val holdMs: Long = 450L) {
    data class Movement(val dx: Float, val dy: Float, val began: Boolean)
    private var downAt: Long? = null
    private var x = 0f
    private var y = 0f
    private var dragging = false
    fun down(rawX: Float, rawY: Float, at: Long) {
        downAt = at; x = rawX; y = rawY; dragging = false
    }
    fun move(rawX: Float, rawY: Float, at: Long, pointers: Int = 1): Movement? {
        if (pointers != 1) { cancel(); return null }
        val start = downAt ?: return null
        if (at < start || at - start < holdMs) return null
        val movement = Movement(rawX - x, rawY - y, !dragging)
        x = rawX; y = rawY; dragging = true
        return movement
    }
    fun cancel() { downAt = null; dragging = false }
}
