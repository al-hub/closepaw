package ai.closepaw.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import ai.closepaw.util.recycleCompat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Small, individually touchable accessibility overlay. No full-screen touch shield.
 * The only continuous control is X: short tap stops; long press then drag repositions.
 */
internal class XStopOverlayHost(
    private val service: AccessibilityService,
    private val onStop: () -> Unit,
) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val logTag = "ClosePawXOverlay"
    // Distinguishes legacy vs modern X hosts without exposing user content.
    private val hostId = Integer.toHexString(System.identityHashCode(this))
    private val preferences = service.getSharedPreferences("x_stop_overlay", 0)
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val sizePx get() = dp(56)
    private val marginPx get() = dp(12)

    /** Identify the safety control window so it cannot be mistaken for app content. */
    fun ownsWindow(windowId: Int): Boolean {
        val button = view ?: return false
        if (windowId < 0) return false
        val node = button.createAccessibilityNodeInfo() ?: return false
        return try { node.windowId == windowId } finally { node.recycleCompat() }
    }

    /** Prevent agent gestures on top of the emergency stop surface. */
    fun covers(rect: Rect): Boolean {
        val button = view ?: return false
        if (!button.isAttachedToWindow) return false
        val xy = IntArray(2)
        button.getLocationOnScreen(xy)
        return Rect.intersects(
            Rect(xy[0], xy[1], xy[0] + sizePx, xy[1] + sizePx),
            rect,
        )
    }

    fun isShowing(): Boolean = view != null

    fun show() {
        if (view != null) {
            Log.d(logTag, "x_show_existing host=$hostId")
            refreshBounds()
            return
        }
        val policy = XHandleGesturePolicy(moveSlopPx = dp(10).toFloat())
        val button = TextView(service).apply {
            text = "×"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "AI 제어 종료, 길게 눌러 이동"
            setOnClickListener { onStop() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 29, 40, 59))
                setStroke(dp(1), Color.argb(190, 140, 195, 255))
            }
            elevation = dp(5).toFloat()
        }
        val lp = WindowManager.LayoutParams(
            sizePx, sizePx, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt("x", defaultX())
            y = preferences.getInt("y", dp(40))
        }
        clamp(lp)
        var touchX = 0f
        var touchY = 0f
        var windowX = 0
        var windowY = 0
        var downAt = 0L
        var hasMoved = false
        var touchActive = false
        val readyVibration = Runnable {
            if (touchActive && view === button) {
                button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
        fun updatePosition(rawX: Float, rawY: Float): Boolean {
            val metrics = service.resources.displayMetrics
            val target = XDragPositionPolicy.position(
                startX = windowX, startY = windowY,
                deltaX = rawX - touchX, deltaY = rawY - touchY,
                screenWidth = metrics.widthPixels, screenHeight = metrics.heightPixels,
                handleSize = sizePx, topInset = dp(24), bottomInset = dp(24),
            )
            val oldX = lp.x
            val oldY = lp.y
            lp.x = target.x
            lp.y = target.y
            if (oldX == lp.x && oldY == lp.y) return false
            if (view !== button) return false
            return try {
                manager.updateViewLayout(button, lp)
                // Some OEMs send ACTION_CANCEL on overlay reposition. Persist on each
                // acknowledged move, not only ACTION_UP, so the last position survives.
                preferences.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                hasMoved = true
                true
            } catch (error: RuntimeException) {
                lp.x = oldX
                lp.y = oldY
                Log.w(logTag, "x_drag_update_failed", error)
                false
            }
        }
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchX = event.rawX
                    touchY = event.rawY
                    windowX = lp.x
                    windowY = lp.y
                    downAt = event.eventTime
                    hasMoved = false
                    touchActive = true
                    button.removeCallbacks(readyVibration)
                    button.postDelayed(readyVibration, 450L)
                    policy.onDown(event.rawX, event.rawY, downAt)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    policy.onMove(event.rawX, event.rawY)
                    if (event.pointerCount == 1 && event.eventTime - downAt >= 450L) {
                        updatePosition(event.rawX, event.rawY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    touchActive = false
                    button.removeCallbacks(readyVibration)
                    val result = policy.onUp(event.rawX, event.rawY, event.eventTime)
                    if (result == XHandleGesturePolicy.Result.TERMINATE) {
                        onStop()
                    } else {
                        if (event.eventTime - downAt >= 450L && event.pointerCount == 1) {
                            updatePosition(event.rawX, event.rawY)
                        }
                        // Only a successful movement stores coordinates.
                        // A cancelled/failed drag cannot corrupt the saved position.
                        if (hasMoved) Log.i(logTag, "x_drag_persisted")
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    touchActive = false
                    button.removeCallbacks(readyVibration)
                    policy.onCancel()
                    // ACTION_CANCEL can follow OEM window reposition; successful
                    // moves are already durable and must not trigger stop.
                    true
                }
                else -> false
            }
        }
        try {
            manager.addView(button, lp)
            view = button
            params = lp
            Log.i(logTag, "x_window_added host=$hostId")
        } catch (error: Exception) {
            Log.w(logTag, "x_window_add_failed host=$hostId", error)
            view = null
            params = null
        }
    }

    fun hide() {
        val existing = view ?: return
        view = null
        params = null
        try {
            manager.removeView(existing)
            Log.i(logTag, "x_window_removed host=$hostId")
        } catch (error: Exception) {
            Log.w(logTag, "x_window_remove_failed host=$hostId", error)
        }
    }

    fun dispose() = hide()

    private fun refreshBounds() {
        val button = view ?: return
        val lp = params ?: return
        val oldX = lp.x
        val oldY = lp.y
        clamp(lp)
        if (oldX != lp.x || oldY != lp.y) {
            try {
                manager.updateViewLayout(button, lp)
                preferences.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
            } catch (_: Exception) { lp.x = oldX; lp.y = oldY }
        }
    }

    private fun defaultX(): Int =
        service.resources.displayMetrics.widthPixels - sizePx - marginPx

    private fun clamp(p: WindowManager.LayoutParams) {
        val metrics = service.resources.displayMetrics
        p.x = p.x.coerceIn(0, (metrics.widthPixels - sizePx).coerceAtLeast(0))
        p.y = p.y.coerceIn(dp(24), (metrics.heightPixels - sizePx - dp(24)).coerceAtLeast(dp(24)))
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), service.resources.displayMetrics,
    ).toInt()
}
