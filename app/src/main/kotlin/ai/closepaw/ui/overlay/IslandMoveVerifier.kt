package ai.closepaw.ui.overlay

data class IslandPosition(val x: Int, val y: Int)

enum class IslandMoveVerdict { MOVED, NOT_MOVED, UNKNOWN }

/** Pure observation verdict: gesture callbacks are not evidence of a UI postcondition. */
object IslandMoveVerifier {
    private const val MIN_DISPLACEMENT_PX = 8L
    fun verify(before: IslandPosition?, after: IslandPosition?, acknowledged: Boolean): IslandMoveVerdict {
        if (!acknowledged || before == null || after == null) return IslandMoveVerdict.UNKNOWN
        val dx = kotlin.math.abs(after.x.toLong() - before.x.toLong())
        val dy = kotlin.math.abs(after.y.toLong() - before.y.toLong())
        return if (dx >= MIN_DISPLACEMENT_PX || dy >= MIN_DISPLACEMENT_PX) IslandMoveVerdict.MOVED else IslandMoveVerdict.NOT_MOVED
    }
}
