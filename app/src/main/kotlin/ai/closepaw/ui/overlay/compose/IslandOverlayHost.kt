package ai.closepaw.ui.overlay.compose

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.HapticFeedbackConstants
import ai.closepaw.ui.overlay.IslandDragGesture
import ai.closepaw.ui.overlay.IslandStatusText
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    private val hostId = Integer.toHexString(System.identityHashCode(this))
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val composeHost = OverlayComposeHost(
        context = service,
        lifecycleOwner = lifecycleOwner,
        savedStateRegistryOwner = savedStateRegistryOwner,
        windowManager = windowManager,
        tag = TAG,
    )

    private var stateHolder: CapsuleStateHolder? = null
    private var readOnlyMode by mutableStateOf(false)
    private val dragGesture = IslandDragGesture()
    private var touchView: View? = null
    private var holdFeedback: Runnable? = null
    private var holdVibrated = false
    private fun cancelTouch() {
        holdFeedback?.let { touchView?.removeCallbacks(it) }
        holdFeedback = null; touchView = null
        dragGesture.cancel()
    }
    private var remoteStatusText by mutableStateOf<String?>(null)

    /** Same window serves bridge-only sessions; never create another status surface. */
    fun setRemoteStatus(text: String?) { remoteStatusText = text }
    private val positions = service.getSharedPreferences("closepaw_island_position", android.content.Context.MODE_PRIVATE)
    private var islandX = positions.getInt("x", -1)
    private var islandY = positions.getInt("y", -1)
    private var fractionX = 0f
    private var fractionY = 0f
    private fun dragBy(dx: Float, dy: Float) {
        val metrics = service.resources.displayMetrics
        val size = composeHost.measuredSize() ?: (dp(280) to dp(64))
        val maxX = (metrics.widthPixels - size.first).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - size.second).coerceAtLeast(0)
        fractionX += dx
        fractionY += dy
        val stepX = fractionX.toInt()
        val stepY = fractionY.toInt()
        fractionX -= stepX
        fractionY -= stepY
        val nextX = ((if (islandX < 0) maxX / 2 else islandX) + stepX).coerceIn(0, maxX)
        val nextY = ((if (islandY < 0) getStatusBarHeight() + dp(88) else islandY) + stepY).coerceIn(0, maxY)
        if (nextX == islandX && nextY == islandY) return
        // Keep the emergency X's real persisted hit area unobstructed.
        val stop = service.getSharedPreferences("x_stop_overlay", 0)
        val stopX = stop.getInt("x", metrics.widthPixels - dp(68))
        val stopY = stop.getInt("y", dp(40))
        if (nextX < stopX + dp(56) && nextX + size.first > stopX &&
            nextY < stopY + dp(56) && nextY + size.second > stopY) return
        try {
            if (!composeHost.updateLayoutParams { it.x = nextX; it.y = nextY }) return
            islandX = nextX
            islandY = nextY
            // Some OEMs cancel touch on window movement; store every acknowledged move.
            persistPosition()
        } catch (error: RuntimeException) {
            Log.w(TAG, "island_drag_update_failed host=$hostId", error)
        }
    }
    private fun persistPosition() {
        if (islandX >= 0 && islandY >= 0) {
            positions.edit().putInt("x", islandX).putInt("y", islandY).apply()
        }
    }

    /** Read-only disables commands; the island itself remains touchable for deliberate dragging. */
    fun setReadOnly(enabled: Boolean) {
        if (readOnlyMode == enabled) return
        readOnlyMode = enabled
    }

    fun show() {
        if (composeHost.isShowing()) return
        val layout = createLayoutParams()
        islandX = layout.x; islandY = layout.y
        composeHost.show(layout, onTouch = ::handleTouch) {
            val holder = stateHolder
            val remote = remoteStatusText
            if (remote != null || holder == null) {
                StatusIslandCompose(
                    text = remote ?: "Working...",
                    dotColor = MaterialTheme.colorScheme.primary,
                    pulsing = true,
                    onClick = if (readOnlyMode) null else onExpandCapsule,
                    onDrag = if (readOnlyMode) null else ::dragBy,
                    onDragEnd = ::persistPosition,
                )
            } else {
                val mode by holder.mode.collectAsState(initial = CapsuleMode.Hidden)
                val turnPhase by holder.turnPhase.collectAsState(initial = null)
                val actionLabel by holder.actionLabel.collectAsState(initial = null)
                val text = if (mode is CapsuleMode.Running) actionLabel ?: "AI 제어 중" else IslandStatusText.label(mode)
                val glowState = deriveGlowState(mode, turnPhase)
                StatusIslandCompose(
                    text = if (text.isBlank()) "Working..." else text,
                    dotColor = glowState.toStatusColor(),
                    pulsing = mode is CapsuleMode.Running,
                    onClick = if (readOnlyMode) null else onExpandCapsule,
                    onDrag = if (readOnlyMode) null else ::dragBy,
                    onDragEnd = ::persistPosition,
                )
            }
        }
        Log.i(TAG, "island_show_requested host=$hostId attached=${composeHost.isShowing()}")
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        if (!readOnlyMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelTouch()
                fractionX = 0f; fractionY = 0f
                touchView = view; holdVibrated = false
                holdFeedback = Runnable {
                    if (touchView === view && !holdVibrated) {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        holdVibrated = true
                    }
                }.also { view.postDelayed(it, 450L) }
                dragGesture.down(event.rawX, event.rawY, event.eventTime)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (event.pointerCount != 1) { cancelTouch(); return true }
                dragGesture.move(event.rawX, event.rawY, event.eventTime, event.pointerCount)?.let {
                    if (it.began && !holdVibrated) {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); holdVibrated = true
                    }
                    dragBy(it.dx, it.dy)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) { persistPosition(); cancelTouch() }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelTouch()
        }
        return true // A short tap never expands or terminates the read-only status island.
    }

    fun hide() {
        cancelTouch()
        if (composeHost.isShowing()) Log.i(TAG, "island_hide host=$hostId")
        composeHost.hide()
    }

    fun isShowing(): Boolean = composeHost.isShowing()
    fun ownsWindow(id: Int): Boolean = composeHost.ownsWindow(id)
    fun covers(rect: android.graphics.Rect): Boolean = composeHost.covers(rect)

    fun startObserving(stateHolder: CapsuleStateHolder, @Suppress("UNUSED_PARAMETER") scope: CoroutineScope) {
        this.stateHolder = stateHolder
    }

    fun dispose() {
        hide()
        composeHost.dispose()
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
            val maxX = (service.resources.displayMetrics.widthPixels - dp(280)).coerceAtLeast(0)
            val maxY = (service.resources.displayMetrics.heightPixels - dp(64)).coerceAtLeast(0)
            x = (if (islandX < 0) maxX / 2 else islandX).coerceIn(0, maxX)
            y = (if (islandY < 0) statusBarHeight + dp(88) else islandY).coerceIn(0, maxY)
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
