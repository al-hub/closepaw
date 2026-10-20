package ai.closepaw.ui.overlay

import kotlin.math.hypot

/**
 * Classifies the single X handle interaction without side effects.
 *
 * A completed short tap requests session termination. A long hold, a drag, or a
 * cancelled gesture must NEVER request termination. Position movement is
 * handled by the overlay host and is intentionally independent of this policy.
 */
internal class XHandleGesturePolicy(
    private val longPressMs: Long = 450L,
    private val moveSlopPx: Float = 16f,
) {
    enum class Result { TERMINATE, KEEP_SESSION }

    private var downAtMs: Long? = null
    private var startX = 0f
    private var startY = 0f
    private var dragged = false

    fun onDown(x: Float, y: Float, atMs: Long) {
        downAtMs = atMs
        startX = x
        startY = y
        dragged = false
    }

    fun onMove(x: Float, y: Float) {
        if (downAtMs == null) return
        if (hypot((x - startX).toDouble(), (y - startY).toDouble()) > moveSlopPx) {
            dragged = true
        }
    }

    fun onUp(x: Float, y: Float, atMs: Long): Result {
        val start = downAtMs ?: return Result.KEEP_SESSION
        onMove(x, y)
        val isShort = atMs >= start && atMs - start < longPressMs
        val result = if (isShort && !dragged) Result.TERMINATE else Result.KEEP_SESSION
        reset()
        return result
    }

    fun onCancel() = reset()

    private fun reset() {
        downAtMs = null
        dragged = false
    }
}
