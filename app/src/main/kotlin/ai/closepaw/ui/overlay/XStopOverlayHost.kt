package ai.closepaw.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
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
    private val preferences = service.getSharedPreferences("x_stop_overlay", 0)
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val sizePx get() = dp(56)
    private val marginPx get() = dp(12)

    fun isShowing(): Boolean = view != null

    fun show() {
        if (view != null) {
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
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchX = event.rawX
                    touchY = event.rawY
                    windowX = lp.x
                    windowY = lp.y
                    downAt = event.eventTime
                    policy.onDown(event.rawX, event.rawY, downAt)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    policy.onMove(event.rawX, event.rawY)
                    if (event.eventTime - downAt >= 450L) {
                        lp.x = windowX + (event.rawX - touchX).toInt()
                        lp.y = windowY + (event.rawY - touchY).toInt()
                        clamp(lp)
                        if (view === button) {
                            try { manager.updateViewLayout(button, lp) }
                            catch (_: IllegalArgumentException) { /* Window detached mid-drag. */ }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val result = policy.onUp(event.rawX, event.rawY, event.eventTime)
                    if (result == XHandleGesturePolicy.Result.TERMINATE) {
                        onStop()
                    } else {
                        preferences.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    policy.onCancel()
                    true
                }
                else -> false
            }
        }
        try {
            manager.addView(button, lp)
            view = button
            params = lp
        } catch (_: Exception) {
            view = null
            params = null
        }
    }

    fun hide() {
        val existing = view ?: return
        view = null
        params = null
        try { manager.removeView(existing) } catch (_: Exception) { }
    }

    fun dispose() = hide()

    private fun refreshBounds() {
        val button = view ?: return
        val lp = params ?: return
        val oldX = lp.x
        val oldY = lp.y
        clamp(lp)
        if (oldX != lp.x || oldY != lp.y) {
            try { manager.updateViewLayout(button, lp) } catch (_: Exception) { }
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
