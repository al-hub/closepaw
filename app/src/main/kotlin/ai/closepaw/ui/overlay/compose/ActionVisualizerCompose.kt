package ai.closepaw.ui.overlay.compose

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import ai.closepaw.ui.theme.ClosePawMotion

internal sealed interface VisualizationItem {
    val id: Long
    val createdAtMs: Long
    val durationMs: Long

    data class Click(
        override val id: Long,
        override val createdAtMs: Long,
        override val durationMs: Long,
        val x: Float,
        val y: Float,
        val longPress: Boolean,
        val gestureDurationMs: Long = durationMs,
    ) : VisualizationItem

    data class Swipe(
        override val id: Long,
        override val createdAtMs: Long,
        override val durationMs: Long,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val scroll: Boolean,
        val gestureDurationMs: Long = durationMs,
    ) : VisualizationItem
}

/** Coordinates are the injector's unmodified screen pixels. Semantic scroll uses a static direction cue. */
@Composable
internal fun ActionVisualizerCompose(items: List<VisualizationItem>, modifier: Modifier = Modifier) {
    val reducedMotion = ClosePawMotion.reducedMotion() || !ValueAnimator.areAnimatorsEnabled()
    var frameTimeMs by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val hostView = LocalView.current
    val screenOrigin = remember { IntArray(2) }
    val light = Color(0xFF80DFEF)
    val violet = Color(0xFFBFC6FF)

    if (!reducedMotion && items.isNotEmpty()) {
        LaunchedEffect(Unit) {
            while (true) withFrameMillis { frameTimeMs = SystemClock.uptimeMillis() }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        hostView.getLocationOnScreen(screenOrigin)
        withTransform({ translate(-screenOrigin[0].toFloat(), -screenOrigin[1].toFloat()) }) {
            items.forEach { item ->
                val elapsed = (frameTimeMs - item.createdAtMs).coerceAtLeast(0)
                when (item) {
                    is VisualizationItem.Click -> {
                        val progress = if (reducedMotion) 0.35f else
                            VisualizationTiming.motionProgress(elapsed, item.gestureDurationMs)
                        val opacity = if (reducedMotion) 0.85f else if (item.longPress)
                            VisualizationTiming.opacity(elapsed, item.gestureDurationMs, item.durationMs - item.gestureDurationMs)
                            else (1f - progress)
                        val at = Offset(item.x, item.y)
                        val color = if (item.longPress) violet else light
                        val radius = if (item.longPress) lerp(28.dp.toPx(), 10.dp.toPx(), progress)
                            else lerp(10.dp.toPx(), 34.dp.toPx(), progress)
                        drawCircle(color.copy(alpha = 0.09f * opacity), radius + 10.dp.toPx(), at)
                        drawCircle(color.copy(alpha = 0.75f * opacity), radius, at, style = Stroke(1.5.dp.toPx()))
                        drawCircle(color.copy(alpha = 0.9f * opacity), 3.dp.toPx(), at)
                    }
                    is VisualizationItem.Swipe -> {
                        val start = Offset(item.startX, item.startY)
                        val end = Offset(item.endX, item.endY)
                        // A node scroll has direction/bounds, not an injected path: never animate a fake touch.
                        val staticDirection = item.scroll || reducedMotion
                        val progress = if (staticDirection) 1f else
                            VisualizationTiming.motionProgress(elapsed, item.gestureDurationMs)
                        val opacity = if (reducedMotion) 0.75f else VisualizationTiming.opacity(
                            elapsed, item.gestureDurationMs, item.durationMs - item.gestureDurationMs)
                        val current = Offset(lerp(start.x, end.x, progress), lerp(start.y, end.y, progress))
                        drawLine(light.copy(alpha = 0.08f * opacity), start, current,
                            strokeWidth = 14.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(light.copy(alpha = 0.7f * opacity), start, current,
                            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                        drawCircle(light.copy(alpha = 0.5f * opacity), 7.dp.toPx(), start, style = Stroke(1.dp.toPx()))
                        if (item.scroll) {
                            val length = (end - start).getDistance()
                            if (length > 0f) {
                                val unit = (end - start) / length
                                val wing = 9.dp.toPx()
                                val perpendicular = Offset(-unit.y, unit.x)
                                drawLine(light.copy(alpha = opacity), end, end - unit * wing + perpendicular * wing,
                                    2.dp.toPx(), StrokeCap.Round)
                                drawLine(light.copy(alpha = opacity), end, end - unit * wing - perpendicular * wing,
                                    2.dp.toPx(), StrokeCap.Round)
                            }
                        } else {
                            drawCircle(light.copy(alpha = 0.13f * opacity), 13.dp.toPx(), current)
                            drawCircle(Color.White.copy(alpha = 0.9f * opacity), 4.dp.toPx(), current)
                        }
                    }
                }
            }
        }
    }
}
