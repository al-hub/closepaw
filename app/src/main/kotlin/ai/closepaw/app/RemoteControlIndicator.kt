package ai.closepaw.app

import ai.closepaw.chatgpt.control.ControlSession
import ai.closepaw.util.recycleCompat
import android.accessibilityservice.AccessibilityService
import android.graphics.*
import android.view.*
import android.widget.Button

/** Full-screen decoration never receives touch; only bounded panel / emergency X windows do. */
internal class RemoteControlIndicator(private val service: AccessibilityService,
    private val session: ControlSession, private val resume: () -> Unit) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val windows = mutableListOf<View>()
    private var border: Border? = null
    private var panel: RemoteControlPanel? = null
    private var stoppedResult = false
    private var panelX = 12
    private var panelY = 80
    private val density get() = service.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private fun owns(view: View, id: Int): Boolean {
        val node = view.createAccessibilityNodeInfo() ?: return false
        return try { node.windowId == id } finally { node.recycleCompat() }
    }
    fun ownsWindow(id: Int) = id >= 0 && windows.any { owns(it, id) }
    fun controlCovers(rect: Rect): Boolean = windows.filter { it !== border }.any {
        val point = IntArray(2); it.getLocationOnScreen(point)
        Rect.intersects(Rect(point[0], point[1], point[0] + it.width, point[1] + it.height), rect)
    }
    fun isAttached() = border?.isAttachedToWindow == true
    /** Rebuild a missing control panel without changing the user-owned session state. */
    fun restorePanel() {
        if (session.state == ControlSession.State.PAUSED && unlocked()) {
            if (border == null || !isAttached()) hide()
            show()
        }
    }
    private fun unlocked() = !service.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
        service.getSystemService(android.os.PowerManager::class.java).isInteractive
    private val guard = object : Runnable {
        override fun run() {
            if (!unlocked()) { session.pause(); hide(); return }
            placePanel(); border?.postDelayed(this, 500)
        }
    }
    fun show(testOnly: Boolean = false) {
        if (session.state != ControlSession.State.ENDED) stoppedResult = false
        if (!unlocked() || (!testOnly && (session.state == ControlSession.State.IDLE ||
                session.state == ControlSession.State.ENDED && !stoppedResult))) { hide(); return }
        if (border == null) {
            try {
                val decoration = Border()
                add(decoration, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    Gravity.TOP or Gravity.START, 0, 0, touchable = false)
                border = decoration
                if (!testOnly) add(closeButton(), dp(56), dp(56), Gravity.TOP or Gravity.RIGHT, dp(12), dp(38))
                decoration.post(guard)
            } catch (_: Exception) { hide(); session.pause(); return }
        }
        // Physical resume temporarily removes the app-covering panel before fresh observation.
        // The independent emergency X remains available throughout reobservation.
        if (session.state == ControlSession.State.REOBSERVING) removePanel()
        if (!testOnly && session.state != ControlSession.State.REOBSERVING && panel == null) {
            try {
                panel = RemoteControlPanel(service, session,
                    resume = { removePanel(); resume() },
                    stop = { stoppedResult = true; session.end(); show() },
                    moved = { dx, dy ->
                        panelX = (panelX - dx / density).toInt()
                        panelY = (panelY - dy / density).toInt()
                        placePanel()
                    }).also { add(it, panelWidth(), WindowManager.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.RIGHT, dp(panelX), dp(panelY)) }
            } catch (_: Exception) { hide(); session.pause(); return }
        }
        panel?.update(stoppedResult)
        placePanel()
        border?.invalidate()
    }
    private fun panelWidth() = minOf(dp(288), service.resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(120))
    private fun removePanel() {
        val attached = panel ?: return
        manager.removeViewImmediate(attached); windows.remove(attached); panel = null
    }
    private fun placePanel() {
        val view = panel ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        val nativeWindows = service.windows.orEmpty()
        val keyboardTop = try {
            nativeWindows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .map { window -> Rect().also { window.getBoundsInScreen(it) } }.minOfOrNull { it.top }
        } finally { nativeWindows.forEach { runCatching { it.recycle() } } }
        val metrics = service.resources.displayMetrics
        val maxX = maxOf(dp(12), metrics.widthPixels - panelWidth() - dp(12))
        val minY = maxOf(dp(24), keyboardTop?.let { metrics.heightPixels - it + dp(12) } ?: dp(24))
        // Optional actions collapse first. If IME consumes almost the entire screen,
        // the emergency row stays on-screen even when it must overlap the keyboard.
        if (view.fitAvailableHeight(metrics.heightPixels - minY - dp(104))) view.post { placePanel() }
        val maxY = maxOf(dp(12), metrics.heightPixels - view.height - dp(104))
        val nextX = dp(panelX).coerceIn(dp(12), maxX)
        val nextY = maxOf(dp(panelY), minY).coerceAtMost(maxY)
        val nextWidth = panelWidth()
        if (params.x != nextX || params.y != nextY || params.width != nextWidth) {
            params.x = nextX; params.y = nextY; params.width = nextWidth
            if (view.isAttachedToWindow) runCatching { manager.updateViewLayout(view, params) }
        }
    }
    private fun closeButton() = Button(service).apply {
        text = "✕"; textSize = 22f; setPadding(0, 0, 0, 0)
        setTextColor(Color.WHITE)
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x4459C9DD),
            android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(21, 45, 62)); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(100, 152, 169))
            }, null)
        contentDescription = "AI 제어 세션 종료 및 패널 닫기"; isFocusable = true
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            stoppedResult = false; session.end(); hide()
        }
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
        windows.clear(); border = null; panel = null
    }
    private inner class Border : PremiumControlDecoration(service, session)
    fun target(rect: Rect) { border?.target(rect) }
    fun action(rect: Rect, kind: String, completed: Boolean?) { border?.action(rect, kind, completed) }
    fun tap(rect: Rect, x: Float, y: Float) { border?.tap(rect, x, y) }
}
