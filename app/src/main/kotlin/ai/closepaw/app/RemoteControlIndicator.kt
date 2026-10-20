package ai.closepaw.app

import ai.closepaw.chatgpt.control.ControlSession
import ai.closepaw.util.recycleCompat
import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.*
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityManager
import android.widget.Button

/** Decorative window passes every touch; only separate 48dp control windows accept input. */
internal class RemoteControlIndicator(private val service: AccessibilityService,
    private val session: ControlSession, private val resume: () -> Unit) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val windows = mutableListOf<View>()
    private var border: Border? = null
    private var pause: Button? = null
    private val density get() = service.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private fun owns(view: View, id: Int): Boolean {
        val node = view.createAccessibilityNodeInfo() ?: return false
        return try { node.windowId == id } finally { node.recycleCompat() }
    }
    fun ownsWindow(id: Int) = id >= 0 && windows.any { owns(it, id) }
    fun controlCovers(rect: Rect): Boolean = windows.filter { it !== border }.any {
        val point = IntArray(2); it.getLocationOnScreen(point)
        Rect(point[0], point[1], point[0] + it.width, point[1] + it.height).contains(rect.centerX(), rect.centerY())
    }
    fun isAttached() = border?.isAttachedToWindow == true
    private fun unlocked() = !service.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
        service.getSystemService(android.os.PowerManager::class.java).isInteractive
    private val guard = object : Runnable {
        override fun run() {
            if (!unlocked()) { session.pause(); hide(); return }
            placePause(); border?.postDelayed(this, 500)
        }
    }
    fun show(testOnly: Boolean = false) {
        if (!unlocked() || (!testOnly && session.state in setOf(ControlSession.State.IDLE, ControlSession.State.ENDED))) { hide(); return }
        if (border == null) {
            try {
                val decoration = Border()
                add(decoration, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    Gravity.TOP or Gravity.START, 0, 0, touchable = false)
                border = decoration
                if (!testOnly) {
                add(button("✕", "AI 제어 완전 종료") { session.end() }, dp(48), dp(48), Gravity.TOP or Gravity.RIGHT, dp(12), dp(38))
                pause = button("⏸", "AI 제어 일시정지") {
                    when (session.state) {
                        ControlSession.State.PAUSED -> resume()
                        ControlSession.State.WAITING -> { session.userResponded(); resume() }
                        else -> session.pause()
                    }
                }.also { add(it, dp(56), dp(56), Gravity.BOTTOM or Gravity.RIGHT, dp(12), dp(80)) }
                }
                decoration.post(guard)
            } catch (error: Exception) { hide(); session.pause(); throw error }
        }
        pause?.apply {
            text = when (session.state) { ControlSession.State.PAUSED -> "▶"; ControlSession.State.WAITING -> "✓"; else -> "⏸" }
            contentDescription = when (session.state) {
                ControlSession.State.PAUSED -> "화면 확인 후 AI 제어 재개"
                ControlSession.State.WAITING -> "사용자 응답 완료 후 화면 확인"
                else -> "AI 제어 일시정지"
            }
        }
        border?.invalidate()
    }
    private fun placePause() {
        val button = pause ?: return
        val params = button.layoutParams as WindowManager.LayoutParams
        val nativeWindows = service.windows.orEmpty()
        val keyboardTop = try {
            nativeWindows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .map { window -> Rect().also { window.getBoundsInScreen(it) } }.minOfOrNull { it.top }
        } finally { nativeWindows.forEach { runCatching { it.recycle() } } }
        val height = service.resources.displayMetrics.heightPixels
        val y = maxOf(dp(80), keyboardTop?.let { height - it + dp(12) } ?: 0)
        if (params.y != y) { params.y = y; manager.updateViewLayout(button, params) }
    }
    private fun button(label: String, description: String, action: () -> Unit) = Button(service).apply {
        text = label; textSize = 20f; setPadding(0, 0, 0, 0)
        setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(0, 65, 85))
        contentDescription = description; isFocusable = false
        setOnClickListener { action(); show() }
    }
    private fun add(view: View, width: Int, height: Int, gravity: Int, x: Int, y: Int, touchable: Boolean = true) {
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { this.gravity = gravity; this.x = x; this.y = y; setTitle("ClosePaw control") }
        manager.addView(view, params); windows.add(view)
    }
    fun hide() {
        border?.removeCallbacks(guard)
        windows.toList().forEach { runCatching { manager.removeViewImmediate(it) } }
        windows.clear(); border = null; pause = null
    }
    private inner class Border : View(service) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var target: Rect? = null
        var targetUntil = 0L
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS; isClickable = false; isFocusable = false }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val waiting = session.state == ControlSession.State.WAITING
            val animate = waiting && ValueAnimator.areAnimatorsEnabled() &&
                !service.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled
            paint.color = if (waiting) Color.rgb(255, 190, 75) else Color.rgb(0, 185, 225)
            paint.alpha = if (animate) (170 + 65 * kotlin.math.sin(SystemClock.elapsedRealtime() / 650.0)).toInt() else 230
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4 * density
            canvas.drawRoundRect(RectF(2 * density, 2 * density, width - 2 * density, height - 2 * density), 18 * density, 18 * density, paint)
            target?.takeIf { SystemClock.elapsedRealtime() < targetUntil }?.let {
                canvas.drawCircle(it.exactCenterX(), it.exactCenterY(), 18 * density, paint)
                canvas.drawRect(it, paint); postInvalidateDelayed(32)
            }
            paint.alpha = 255; paint.style = Paint.Style.FILL; paint.textSize = 13 * resources.displayMetrics.scaledDensity
            val label = session.label
            val labelWidth = minOf(paint.measureText(label) + 24 * density, width - 100 * density)
            val left = (width - labelWidth) / 2; val top = 94 * density
            paint.color = Color.rgb(0, 65, 85)
            canvas.drawRoundRect(RectF(left, top, left + labelWidth, top + 34 * density), 10 * density, 10 * density, paint)
            paint.color = Color.WHITE
            canvas.save(); canvas.clipRect(left, top, left + labelWidth, top + 34 * density)
            canvas.drawText(label, left + 8 * density, top + 23 * density, paint); canvas.restore()
            if (animate) postInvalidateDelayed(33)
        }
    }
    fun target(rect: Rect) { border?.apply { target = Rect(rect); targetUntil = SystemClock.elapsedRealtime() + 650; invalidate() } }
}
