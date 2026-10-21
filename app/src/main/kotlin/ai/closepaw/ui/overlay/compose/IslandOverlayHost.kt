package ai.closepaw.ui.overlay.compose

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import ai.closepaw.protocol.TurnPhase
import ai.closepaw.ui.capsule.surface.toStatusColor
import ai.closepaw.ui.overlay.CapsuleStateHolder
import ai.closepaw.ui.overlay.model.CapsuleMode
import ai.closepaw.ui.overlay.model.deriveGlowState
import kotlinx.coroutines.CoroutineScope

class IslandOverlayHost(
    private val service: AccessibilityService,
    lifecycleOwner: LifecycleOwner,
    savedStateRegistryOwner: SavedStateRegistryOwner,
    private val onExpandCapsule: () -> Unit,
) {
    companion object {
        private const val TAG = "IslandOverlayHost"
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val composeHost = OverlayComposeHost(
        context = service,
        lifecycleOwner = lifecycleOwner,
        savedStateRegistryOwner = savedStateRegistryOwner,
        windowManager = windowManager,
        tag = TAG,
    )

    private var stateHolder: CapsuleStateHolder? = null
    private var readOnly = false
    private val positionPrefs = service.getSharedPreferences("island_overlay_position", 0)
    private var dragDownX = 0f
    private var dragDownY = 0f
    private var startX = 0
    private var startY = 0
    private var downTime = 0L
    private var dragging = false

    /** Status content is read-only; the bounded window still accepts long-press dragging. */
    fun setReadOnly(enabled: Boolean) {
        if (readOnly == enabled) return
        readOnly = enabled
        if (composeHost.isShowing()) {
            hide()
            show()
        }
    }

    fun show() {
        if (composeHost.isShowing()) return
        composeHost.show(createLayoutParams(), onTouch = if (readOnly) ::handleDragTouch else null) {
            val holder = stateHolder
            if (holder == null) {
                StatusIslandCompose(
                    text = "Working...",
                    dotColor = MaterialTheme.colorScheme.primary,
                    pulsing = true,
                    onClick = if (readOnly) null else onExpandCapsule,
                )
            } else {
                val mode by holder.mode.collectAsState(initial = CapsuleMode.Hidden)
                val turnPhase by holder.turnPhase.collectAsState(initial = null)
                val text = modeText(mode)
                val glowState = deriveGlowState(mode, turnPhase)
                StatusIslandCompose(
                    text = if (text.isBlank()) "Working..." else text,
                    dotColor = glowState.toStatusColor(),
                    pulsing = mode is CapsuleMode.Running,
                    onClick = if (readOnly) null else onExpandCapsule,
                )
            }
        }
        Log.i(TAG, "Status island shown")
    }

    private fun handleDragTouch(view: android.view.View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragDownX = event.rawX; dragDownY = event.rawY
                downTime = event.eventTime
                dragging = false
                val p = view.layoutParams as? WindowManager.LayoutParams
                startX = p?.x ?: 0; startY = p?.y ?: 0
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) return true
                if (event.eventTime - downTime >= 450L) {
                    if (!dragging) {
                        dragging = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    moveIsland(view, event.rawX, event.rawY)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging && event.pointerCount == 1) moveIsland(view, event.rawX, event.rawY)
                dragging = false
                return true // short tap is deliberately inert
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; return true }
            else -> return true
        }
    }

    private fun moveIsland(view: android.view.View, rawX: Float, rawY: Float) {
        val metrics = service.resources.displayMetrics
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        val x = (startX + (rawX - dragDownX).toInt()).coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
        val y = (startY + (rawY - dragDownY).toInt()).coerceIn(dp(24), (metrics.heightPixels - height - dp(24)).coerceAtLeast(dp(24)))
        // Keep the emergency X's reserved hit rectangle unobstructed.
        val xPrefs = service.getSharedPreferences("x_stop_overlay", 0)
        val stopSize = dp(56)
        val stopX = xPrefs.getInt("x", metrics.widthPixels - stopSize - dp(12))
        val stopY = xPrefs.getInt("y", dp(40))
        if (x < stopX + stopSize && x + width > stopX && y < stopY + stopSize && y + height > stopY) return
        composeHost.updateLayoutParams { it.gravity = Gravity.TOP or Gravity.START; it.x = x; it.y = y }
        positionPrefs.edit().putInt("x", x).putInt("y", y).apply()
    }

    fun hide() {
        composeHost.hide()
    }

    fun isShowing(): Boolean = composeHost.isShowing()

    fun startObserving(stateHolder: CapsuleStateHolder, @Suppress("UNUSED_PARAMETER") scope: CoroutineScope) {
        this.stateHolder = stateHolder
    }

    fun dispose() {
        hide()
        composeHost.dispose()
    }

    private fun modeText(mode: CapsuleMode): String = when (mode) {
        is CapsuleMode.Running -> mode.thought
        is CapsuleMode.TakeoverPending -> "Handing over..."
        is CapsuleMode.Takeover -> "Paused"
        is CapsuleMode.WaitingForInput -> "Awaiting response"
        is CapsuleMode.WaitingForAction -> "Action needed"
        is CapsuleMode.WaitingForApproval -> "Allow ClosePaw to operate ${mode.appLabel}?"
        is CapsuleMode.Done -> "Done: ${mode.message.take(18)}"
        is CapsuleMode.Error -> "Error: ${mode.message.take(18)}"
        is CapsuleMode.Hidden -> ""
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val statusBarHeight = getStatusBarHeight()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val metrics = service.resources.displayMetrics
            x = positionPrefs.getInt("x", metrics.widthPixels / 4).coerceIn(0, (metrics.widthPixels - dp(240)).coerceAtLeast(0))
            y = positionPrefs.getInt("y", statusBarHeight + dp(4)).coerceIn(dp(24), (metrics.heightPixels - dp(48)).coerceAtLeast(dp(24)))
        }
    }

    private fun getStatusBarHeight(): Int {
        val resId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) service.resources.getDimensionPixelSize(resId) else dp(24)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            service.resources.displayMetrics
        ).toInt()
    }
}
