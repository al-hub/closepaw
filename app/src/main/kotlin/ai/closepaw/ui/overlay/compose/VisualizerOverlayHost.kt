package ai.closepaw.ui.overlay.compose

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.app.KeyguardManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class VisualizerOverlayHost(
    private val service: AccessibilityService,
    lifecycleOwner: LifecycleOwner,
    savedStateRegistryOwner: SavedStateRegistryOwner,
    private val renderAllowed: () -> Boolean = { true },
) {
    companion object {
        private const val TAG = "VisualizerOverlayHost"
        private const val CLICK_DURATION_MS = 500L
        private const val SWIPE_EXTRA_DURATION_MS = 400L
        private const val MAX_ITEMS = 6
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val composeHost = OverlayComposeHost(
        context = service,
        lifecycleOwner = lifecycleOwner,
        savedStateRegistryOwner = savedStateRegistryOwner,
        windowManager = windowManager,
        tag = TAG,
    )
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val items = MutableStateFlow<List<VisualizationItem>>(emptyList())
    private val nextId = AtomicLong(1)

    private var disposed = false
    private var guardJob: Job? = null
    private var receiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { clear() }
    }

    private fun mayRender() = !disposed && renderAllowed() &&
        service.getSystemService(PowerManager::class.java).isInteractive &&
        !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Synchronously drops effects on pause/takeover; no automatic replay after resume. */
    fun clear() {
        items.value = emptyList()
        guardJob?.cancel(); guardJob = null
        composeHost.hide()
        if (receiverRegistered) {
            runCatching { service.unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
    }

    fun showClick(x: Float, y: Float, longPress: Boolean, holdDurationMs: Long = CLICK_DURATION_MS) {
        if (!mayRender()) { clear(); return }
        ensureOverlay()
        val item = VisualizationItem.Click(
            id = nextId.getAndIncrement(),
            createdAtMs = SystemClock.uptimeMillis(),
            durationMs = if (longPress) holdDurationMs.coerceAtLeast(1) + SWIPE_EXTRA_DURATION_MS else CLICK_DURATION_MS,
            gestureDurationMs = if (longPress) holdDurationMs.coerceAtLeast(1) else CLICK_DURATION_MS,
            x = x,
            y = y,
            longPress = longPress,
        )
        addItem(item)
    }

    fun showSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        scroll: Boolean,
    ) {
        if (!mayRender()) { clear(); return }
        ensureOverlay()
        val item = VisualizationItem.Swipe(
            id = nextId.getAndIncrement(),
            createdAtMs = SystemClock.uptimeMillis(),
            durationMs = durationMs.coerceAtLeast(1) + SWIPE_EXTRA_DURATION_MS,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            gestureDurationMs = durationMs.coerceAtLeast(1),
            scroll = scroll,
        )
        addItem(item)
    }

    fun dispose() {
        disposed = true
        clear()
        composeHost.dispose()
        scope.cancel()
    }

    private fun ensureOverlay() {
        if (composeHost.isShowing()) return
        if (!receiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(screenReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                service.registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            }
            receiverRegistered = true
        }
        composeHost.show(createLayoutParams()) {
            val renderItems by items.collectAsState(initial = emptyList())
            ActionVisualizerCompose(items = renderItems)
        }
        Log.d(TAG, "Visualizer overlay shown")
    }

    private fun addItem(item: VisualizationItem) {
        items.update { (it + item).takeLast(MAX_ITEMS) }
        if (guardJob?.isActive != true) guardJob = scope.launch {
            while (items.value.isNotEmpty()) {
                if (!mayRender()) { clear(); return@launch }
                val now = SystemClock.uptimeMillis()
                items.update { list -> list.filter { now - it.createdAtMs < it.durationMs } }
                if (items.value.isNotEmpty()) delay(32)
            }
            clear()
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
    }
}
