package ai.closepaw.ui.overlay.compose

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
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
    private var readOnly = false
    private val positions = service.getSharedPreferences("closepaw_island_position", android.content.Context.MODE_PRIVATE)
    private var islandX = positions.getInt("x", -1)
    private var islandY = positions.getInt("y", -1)
    private var fractionX = 0f
    private var fractionY = 0f
    private fun dragBy(dx: Float, dy: Float) {
        val metrics = service.resources.displayMetrics
        val maxX = (metrics.widthPixels - dp(280)).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - dp(64)).coerceAtLeast(0)
        fractionX += dx
        fractionY += dy
        val stepX = fractionX.toInt()
        val stepY = fractionY.toInt()
        fractionX -= stepX
        fractionY -= stepY
        islandX = ((if (islandX < 0) maxX / 2 else islandX) + stepX).coerceIn(0, maxX)
        islandY = ((if (islandY < 0) getStatusBarHeight() + dp(88) else islandY) + stepY).coerceIn(0, maxY)
        composeHost.updateLayoutParams { it.x = islandX; it.y = islandY }
    }
    private fun persistPosition() {
        if (islandX >= 0 && islandY >= 0) {
            positions.edit().putInt("x", islandX).putInt("y", islandY).apply()
        }
    }

    /** Read-only disables commands; the island itself remains touchable for deliberate dragging. */
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
        composeHost.show(createLayoutParams()) {
            val holder = stateHolder
            if (holder == null) {
                StatusIslandCompose(
                    text = "Working...",
                    dotColor = MaterialTheme.colorScheme.primary,
                    pulsing = true,
                    onClick = if (readOnly) null else onExpandCapsule,
                    onDrag = ::dragBy,
                    onDragEnd = ::persistPosition,
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
                    onDrag = ::dragBy,
                    onDragEnd = ::persistPosition,
                )
            }
        }
        Log.i(TAG, "island_show_requested host=$hostId attached=${composeHost.isShowing()}")
    }

    fun hide() {
        if (composeHost.isShowing()) Log.i(TAG, "island_hide host=$hostId")
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
