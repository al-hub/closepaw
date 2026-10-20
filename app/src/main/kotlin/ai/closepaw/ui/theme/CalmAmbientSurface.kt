package ai.closepaw.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Cached ambient depth, with no idle animation or per-frame bitmap allocations. */
fun Modifier.calmAmbientSurface(): Modifier = composed {
    val blue = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val cyan = MaterialTheme.colorScheme.secondary.copy(alpha = 0.05f)
    drawWithCache {
        val upper = Brush.radialGradient(listOf(blue, Color.Transparent),
            center = Offset(size.width * 0.9f, size.height * 0.1f),
            radius = maxOf(size.width, size.height) * 0.65f)
        val lower = Brush.radialGradient(listOf(cyan, Color.Transparent),
            center = Offset(size.width * 0.1f, size.height * 0.8f),
            radius = maxOf(size.width, size.height) * 0.5f)
        onDrawBehind { drawRect(upper); drawRect(lower) }
    }
}
