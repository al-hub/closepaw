package ai.closepaw.ui.overlay.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import android.os.SystemClock
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.closepaw.BuildConfig
import ai.closepaw.protocol.compactThought
import ai.closepaw.ui.capsule.surface.StatusPawGlyph
import ai.closepaw.ui.theme.ClosePawMotion
import ai.closepaw.ui.theme.closePaw
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatusIslandCompose(
    text: String,
    dotColor: Color,
    pulsing: Boolean,
    onClick: (() -> Unit)?,
    onDragStart: (() -> Unit)? = null,
    onDrag: ((Float, Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    userAction: String? = null,
    fixedWidthDp: Dp? = null,
    fixedHeightDp: Dp? = null,
) {
    val shape = MaterialTheme.shapes.large
    val spacing = MaterialTheme.closePaw.spacing
    val reducedMotion = ClosePawMotion.reducedMotion()
    val localView = LocalView.current
    val appContext = LocalContext.current.applicationContext
    var debugQaErrors by remember { mutableIntStateOf(0) }
    var debugQaStatus by remember { mutableStateOf(DebugQaIslandStatus.UNKNOWN) }
    if (BuildConfig.DEBUG) {
        // Red marker only, not a changing accessibility-tree transcription of
        // QA steps. Read the private tiny status file off the UI thread.
        LaunchedEffect(appContext) {
            while (isActive) {
                val displayState = withContext(Dispatchers.IO) {
                    val nowEpoch = System.currentTimeMillis()
                    val nowElapsed = SystemClock.elapsedRealtime()
                    val errors = runCatching {
                        val file = File(appContext.filesDir, "debug-qa/live-state.json")
                        if (!file.isFile || file.length() !in 1L..2048L) return@runCatching 0
                        val state = Json.parseToJsonElement(file.readText()).jsonObject
                        val updated = state["updated_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0L
                        if (nowEpoch - updated !in 0L..20_000L) 0
                        else (state["error_count"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 99)
                    }.getOrDefault(0)
                    val live = runCatching {
                        val file = File(appContext.filesDir, "debug-qa/runner-status.json")
                        if (!file.isFile || file.length() !in 1L..2048L) null else file.readText()
                    }.getOrNull()
                    val qaStatus = DebugQaIslandStatusPolicy.decode(
                        live, nowElapsed, nowEpoch, android.os.Process.getStartElapsedRealtime())
                    (if (qaStatus.state in setOf("RUNNING", "WAITING")) errors else 0) to qaStatus
                }
                debugQaErrors = displayState.first
                debugQaStatus = displayState.second
                delay(1000L)
            }
        }
    }
    val useFixedCompactSize = !expanded && fixedWidthDp != null && fixedHeightDp != null
    Surface(
        modifier = modifier
            .then(
                if (useFixedCompactSize) Modifier.size(width = fixedWidthDp!!, height = fixedHeightDp!!)
                else Modifier.minimumInteractiveComponentSize()
            )
            .clip(shape)
            .then(if (onDrag != null) Modifier.pointerInput(onDrag) {
                val screen = IntArray(2)
                var previousX = 0f
                var previousY = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { point ->
                        localView.getLocationOnScreen(screen)
                        previousX = screen[0] + point.x
                        previousY = screen[1] + point.y
                        localView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onDragStart?.invoke()
                    },
                    onDragEnd = { onDragEnd?.invoke() },
                    onDragCancel = { onDragEnd?.invoke() },
                    onDrag = { change, _ ->
                        change.consume()
                        // Local pointer coordinates change when the overlay window moves.
                        // Compare screen coordinates so continuous dragging follows the finger.
                        localView.getLocationOnScreen(screen)
                        val rawX = screen[0] + change.position.x
                        val rawY = screen[1] + change.position.y
                        val dx = rawX - previousX
                        val dy = rawY - previousY
                        previousX = rawX
                        previousY = rawY
                        onDrag(dx, dy)
                    },
                )
            } else Modifier)
            .then(if (onClick != null) Modifier.clickable(
                role = Role.Button,
                onClickLabel = "Open current app",
                onClick = onClick,
            ) else Modifier)
            .semantics {
                contentDescription = if (userAction != null) userAction else "Status: $text"
            },
        shape = shape,
        // Debug-only orange surface: immediately distinguish the live floating island.
        color = if (BuildConfig.DEBUG) Color(0xFFFFD3A0) else MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .then(if (userAction != null && !reducedMotion) userActionWave(userAction) else Modifier)
                .defaultMinSize(minHeight = 32.dp)
                .padding(horizontal = spacing.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            if (BuildConfig.DEBUG) {
                Text(
                    text = "DEBUG · QA " + debugQaStatus.state.replace("DIAGNOSTIC_COMPLETE", "DIAG DONE") +
                        when (debugQaStatus.export) {
                            "SAVED" -> " · JSON saved"
                            "FAILED" -> " · JSON failed"
                            else -> debugQaStatus.stage?.let { " · " + it.replace('_', ' ').take(24) }.orEmpty()
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8B3600),
                    maxLines = 1,
                    modifier = Modifier.widthIn(max = if (useFixedCompactSize) 110.dp else 195.dp),
                    overflow = TextOverflow.Ellipsis,
                )
                if (debugQaErrors > 0) {
                    Text(
                        text = "●" + debugQaErrors,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFB00020),
                        maxLines = 1,
                    )
                }
            }
            StatusPawGlyph(
                color = dotColor,
                size = 14.dp,
            )
            val textModifier = if (useFixedCompactSize) Modifier.weight(1f) else Modifier.widthIn(max = 220.dp)
            if (userAction != null || reducedMotion) {
                Text(
                    text = userAction ?: if (expanded) text else compactThought(text),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded && userAction == null) 8 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = textModifier,
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) 8 else 1,
                    modifier = textModifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        velocity = 30.dp,
                        initialDelayMillis = 1500,
                    ),
                )
            }
        }
    }
}

/** A decorative sweep behind stationary text. No frame loop runs during the rest interval. */
@Composable
private fun userActionWave(action: String): Modifier {
    val position = remember(action) { Animatable(0f) }
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    LaunchedEffect(action) {
        while (isActive) {
            position.snapTo(0f)
            position.animateTo(1f, tween(ClosePawMotion.UserActionSweep, easing = LinearEasing))
            delay(ClosePawMotion.UserActionRest.toLong())
        }
    }
    return Modifier.drawBehind {
        val fraction = position.value
        if (fraction <= 0f || fraction >= 1f || size.width <= 0f) return@drawBehind
        val half = size.width * 0.19f
        val center = -half + fraction * (size.width + 2f * half)
        drawRect(Brush.horizontalGradient(
            listOf(Color.Transparent, tint, Color.Transparent),
            startX = center - half,
            endX = center + half,
        ))
    }
}
