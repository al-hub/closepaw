package ai.closepaw.ui.overlay.compose

/** Actual gesture timing is independent of the decorative afterglow. */
internal object VisualizationTiming {
    fun motionProgress(elapsedMs: Long, gestureDurationMs: Long): Float =
        (elapsedMs.coerceAtLeast(0).toFloat() / gestureDurationMs.coerceAtLeast(1)).coerceIn(0f, 1f)

    fun opacity(elapsedMs: Long, gestureDurationMs: Long, fadeDurationMs: Long): Float =
        (1f - (elapsedMs - gestureDurationMs.coerceAtLeast(1)).coerceAtLeast(0).toFloat() /
            fadeDurationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
}
