package ai.closepaw.ui.overlay

import kotlin.math.roundToInt

/**
 * Deterministic compact window size for the floating status island.
 *
 * Guarantees:
 * - While unexpanded (`expanded == false`), window width and height depend ONLY on
 *   display geometry, density, fontScale, emergency-X clearance, and debug badge mode —
 *   NEVER on live status text, QA stage string, error count badge, or userAction prompt.
 *   This eliminates self-inflicted WindowManager `WINDOWS_CHANGE_BOUNDS` churn during
 *   screen captures and gesture validation.
 * - Compact width is strictly smaller than the historical WRAP_CONTENT ceiling and leaves
 *   clearance for the emergency X stop button and persisted top coordinates (e.g. (43, 59)),
 *   so it never introduces new IME or app occlusion.
 * - Explicit user expansion (`expanded == true`) returns [wrapContent] = true so multi-line
 *   detail inspection still works and triggers normal fail-closed display invalidation.
 */
internal data class IslandWindowSize(
    val widthPx: Int,
    val heightPx: Int,
    val wrapContent: Boolean = false,
)

internal object IslandWindowSizePolicy {
    private const val COMPACT_WIDTH_DP_RELEASE = 224f
    private const val COMPACT_WIDTH_DP_DEBUG = 248f
    private const val MIN_WIDTH_DP = 140f
    private const val MIN_HEIGHT_DP = 48f
    private const val MAX_HEIGHT_DP = 96f
    private const val STOP_MARGIN_DP = 16f
    private const val MIN_STOP_CLEARANCE_DP = 72f
    private const val LEFT_ALLOWANCE_DP = 24f
    private const val MIN_LEFT_ALLOWANCE_PX = 48

    fun resolve(
        screenWidthPx: Int,
        screenHeightPx: Int,
        density: Float,
        fontScale: Float = 1f,
        stopWidthPx: Int = 0,
        isDebug: Boolean = false,
        expanded: Boolean = false,
    ): IslandWindowSize {
        if (expanded || screenWidthPx <= 0 || screenHeightPx <= 0 || density <= 0f) {
            return IslandWindowSize(widthPx = 0, heightPx = 0, wrapContent = true)
        }
        fun dpPx(dp: Float): Int = (dp * density).roundToInt().coerceAtLeast(1)

        val preferredWidthPx = dpPx(if (isDebug) COMPACT_WIDTH_DP_DEBUG else COMPACT_WIDTH_DP_RELEASE)
        val stopClearancePx = maxOf(
            if (stopWidthPx > 0) stopWidthPx + dpPx(STOP_MARGIN_DP) else 0,
            dpPx(MIN_STOP_CLEARANCE_DP),
        )
        val leftAllowancePx = maxOf(dpPx(LEFT_ALLOWANCE_DP), MIN_LEFT_ALLOWANCE_PX)
        val minWidthPx = dpPx(MIN_WIDTH_DP)
        val safeCompactWidthPx = screenWidthPx - stopClearancePx - leftAllowancePx
        if (safeCompactWidthPx < minWidthPx) {
            return IslandWindowSize(widthPx = 0, heightPx = 0, wrapContent = true)
        }
        val widthPx = minOf(preferredWidthPx, safeCompactWidthPx).coerceIn(minWidthPx, safeCompactWidthPx)

        val safeFontScale = fontScale.coerceIn(0.85f, 3.0f)
        val screenHeightDp = screenHeightPx / density
        val maxHeightDp = minOf(MAX_HEIGHT_DP, (screenHeightDp * 0.25f).coerceAtLeast(MIN_HEIGHT_DP))
        val compactHeightDp = maxOf(MIN_HEIGHT_DP, 16f * safeFontScale + 16f)
            .coerceAtMost(maxHeightDp)
        val heightPx = dpPx(compactHeightDp).coerceIn(1, screenHeightPx)

        return IslandWindowSize(widthPx = widthPx, heightPx = heightPx, wrapContent = false)
    }
}
