package ai.closepaw.ui.overlay.compose

import android.content.Context
import android.util.Log
import android.view.WindowManager
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.graphics.Rect
import ai.closepaw.util.recycleCompat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import ai.closepaw.ui.theme.ClosePawTheme

/**
 * Small utility for WindowManager overlays backed by ComposeView.
 */
class OverlayComposeHost(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedStateRegistryOwner: SavedStateRegistryOwner,
    private val windowManager: WindowManager,
    private val tag: String,
) {
    private var composeView: View? = null
    private var params: WindowManager.LayoutParams? = null

    fun isShowing(): Boolean = composeView != null

    fun show(
        layoutParams: WindowManager.LayoutParams,
        onTouch: ((View, MotionEvent) -> Boolean)? = null,
        content: @Composable () -> Unit,
    ) {
        if (composeView != null) return
        try {
            val view = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
                setContent {
                    ClosePawTheme {
                        content()
                    }
                }
            }
            // Route active-island gestures before Compose's local-coordinate recognizers.
            // Other overlays and idle content retain their usual Compose event dispatch.
            val root = if (onTouch == null) view else object : FrameLayout(context) {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean =
                    onTouch(this, event) || super.dispatchTouchEvent(event)
            }.apply { addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)) }
            windowManager.addView(root, layoutParams)
            composeView = root
            params = layoutParams
        } catch (e: Exception) {
            Log.e(tag, "Failed to show Compose overlay", e)
        }
    }

    fun hide() {
        val view = composeView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(tag, "Failed to remove Compose overlay", e)
        }
        composeView = null
        params = null
    }

    fun updateLayoutParams(update: (WindowManager.LayoutParams) -> Unit): Boolean {
        val view = composeView ?: return false
        val layoutParams = params ?: return false
        val oldX = layoutParams.x
        val oldY = layoutParams.y
        update(layoutParams)
        return try {
            windowManager.updateViewLayout(view, layoutParams)
            true
        } catch (e: Exception) {
            layoutParams.x = oldX
            layoutParams.y = oldY
            Log.w(tag, "Failed to update Compose overlay params", e)
            false
        }
    }

    fun measuredSize(): Pair<Int, Int>? = composeView?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width to it.height }

    fun getWindowToken() = composeView?.windowToken

    fun ownsWindow(id: Int): Boolean {
        if (id < 0) return false
        val node = composeView?.createAccessibilityNodeInfo() ?: return false
        return try { node.windowId == id } finally { node.recycleCompat() }
    }

    fun covers(rect: Rect): Boolean {
        val view = composeView ?: return false
        if (!view.isAttachedToWindow) return false
        val xy = IntArray(2)
        view.getLocationOnScreen(xy)
        return Rect.intersects(Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height), rect)
    }

    fun dispose() {
        hide()
    }
}
