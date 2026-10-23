package ai.closepaw.ui.overlay

data class IslandPosition(val x: Int, val y: Int)

enum class IslandMoveVerdict { MOVED, NOT_MOVED, UNKNOWN }

/** Pure observation verdict: gesture callbacks are not evidence of a UI postcondition. */
object IslandMoveVerifier {
    fun verify(before: IslandPosition?, after: IslandPosition?, acknowledged: Boolean): IslandMoveVerdict {
        if (!acknowledged || before == null || after == null) return IslandMoveVerdict.UNKNOWN
        return if (before != after) IslandMoveVerdict.MOVED else IslandMoveVerdict.NOT_MOVED
    }
}
