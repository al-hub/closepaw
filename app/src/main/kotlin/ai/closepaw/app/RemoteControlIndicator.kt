package ai.closepaw.app

import ai.closepaw.util.recycleCompat
import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Service-owned, noninteractive border. Never takes focus, intercepts touch or stores screen data. */
internal class RemoteControlIndicator(private val service: AccessibilityService) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    fun ownsWindow(id: Int): Boolean {
        if (id < 0) return false
        val attached = view ?: return false
        // View's direct accessibilityWindowId accessor is hidden; use the public node API.
        val node = attached.createAccessibilityNodeInfo() ?: return false
        return try { node.windowId == id } finally { node.recycleCompat() }
    }

    fun isAttached(): Boolean = view?.isAttachedToWindow == true
    fun show() {
        if (view != null) return
        val indicator = object : View(service) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val density = resources.displayMetrics.density
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                paint.color = Color.rgb(0, 185, 225)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4 * density
                val inset = paint.strokeWidth / 2
                canvas.drawRoundRect(RectF(inset, inset, width - inset, height - inset),
                    18 * density, 18 * density, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(0, 65, 85)
                val label = "ClosePaw 자동 제어"
                paint.textSize = 13 * density
                val labelWidth = paint.measureText(label) + 24 * density
                val left = (width - labelWidth) / 2
                val top = 38 * density
                canvas.drawRoundRect(RectF(left, top, left + labelWidth, top + 29 * density),
                    10 * density, 10 * density, paint)
                paint.color = Color.WHITE
                canvas.drawText(label, left + 12 * density, top + 20 * density, paint)
            }
        }.apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isClickable = false
            isFocusable = false
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; setTitle("ClosePaw remote activity") }
        // Only register the exact view after successful attachment; never exempt arbitrary overlays.
        ai.closepaw.chatgpt.control.ControlDiagnostics.record("indicator", "attach_attempt")
        try {
            manager.addView(indicator, params)
            view = indicator
            ai.closepaw.chatgpt.control.ControlDiagnostics.record("indicator", "attached")
        } catch (error: Exception) {
            ai.closepaw.chatgpt.control.ControlDiagnostics.record("indicator", "attach_failed")
            throw error
        }
    }

    fun hide() {
        val attached = view ?: return
        try {
            manager.removeViewImmediate(attached)
            ai.closepaw.chatgpt.control.ControlDiagnostics.record("indicator", "removed")
        } catch (error: Exception) {
            ai.closepaw.chatgpt.control.ControlDiagnostics.record("indicator", "remove_failed")
            throw error
        } finally { view = null }
    }
}
