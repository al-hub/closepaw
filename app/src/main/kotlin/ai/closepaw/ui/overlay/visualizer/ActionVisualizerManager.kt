package ai.closepaw.ui.overlay.visualizer

import android.accessibilityservice.AccessibilityService
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import ai.closepaw.ui.overlay.compose.VisualizerOverlayHost
import ai.closepaw.ui.overlay.model.CapsuleContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * Compose-backed touch action visualizer.
 */
class ActionVisualizerManager(
    context: AccessibilityService,
    lifecycleOwner: LifecycleOwner,
    savedStateRegistryOwner: SavedStateRegistryOwner,
    private val renderContext: StateFlow<CapsuleContext>,
    private val renderAllowed: () -> Boolean = { true },
) {
    private val overlayHost = VisualizerOverlayHost(
        service = context,
        lifecycleOwner = lifecycleOwner,
        savedStateRegistryOwner = savedStateRegistryOwner,
        renderAllowed = { shouldRender() },
    )

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    init {
        scope.launch { renderContext.collect { if (it != CapsuleContext.SCREEN_VIEWING) clear() } }
    }

    @Volatile
    var enabled: Boolean = true
        set(value) { field = value; if (!value) scope.launch { clear() } }

    fun clear() { overlayHost.clear() }

    fun showClick(x: Float, y: Float, longPress: Boolean = false, holdDurationMs: Long = 500) {
        if (!shouldRender()) return
        overlayHost.showClick(x = x, y = y, longPress = longPress, holdDurationMs = holdDurationMs)
    }

    fun showSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ) {
        if (!shouldRender()) return
        overlayHost.showSwipe(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = durationMs,
            scroll = false,
        )
    }

    fun showScrollAsSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ) {
        if (!shouldRender()) return
        overlayHost.showSwipe(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = durationMs,
            scroll = true,
        )
    }

    fun dispose() {
        overlayHost.dispose()
        scope.cancel()
    }

    private fun shouldRender(): Boolean =
        enabled && renderAllowed() && renderContext.value == CapsuleContext.SCREEN_VIEWING
}
