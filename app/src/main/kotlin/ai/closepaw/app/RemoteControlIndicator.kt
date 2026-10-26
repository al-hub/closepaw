package ai.closepaw.app

import ai.closepaw.chatgpt.control.ControlSession
import ai.closepaw.util.recycleCompat
import android.accessibilityservice.AccessibilityService
import android.graphics.*
import android.view.*
import ai.closepaw.ui.overlay.XStopOverlayHost

/** Full-screen decoration never receives touch; only bounded panel / emergency X windows do. */
internal class RemoteControlIndicator(private val service: AccessibilityService,
    private val session: ControlSession,
    private val stopOtherSessions: () -> Unit = {}, private val resume: () -> Unit) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val windows = mutableListOf<View>()
    private var border: Border? = null
    private var panel: RemoteControlPanel? = null
    private val xStopOverlay = XStopOverlayHost(service) {
        stoppedResult = false
        session.end()
        hide()
        stopOtherSessions()
    }
    private var stoppedResult = false
    private var panelX = 12
    private var panelY = 80
    private val density get() = service.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private fun owns(view: View, id: Int): Boolean {
        val node = view.createAccessibilityNodeInfo() ?: return false
        return try { node.windowId == id } finally { node.recycleCompat() }
    }
    fun ownsWindow(id: Int) = id >= 0 && (windows.any { owns(it, id) } || xStopOverlay.ownsWindow(id))
    internal fun decorationScreenshotMask(id: Int? = null): Region? =
        border?.takeIf { id == null || owns(it,id) }?.screenshotMask()
    fun controlCovers(rect: Rect): Boolean = xStopOverlay.covers(rect) || windows.filter { it !== border }.any {
        val point = IntArray(2); it.getLocationOnScreen(point)
        Rect.intersects(Rect(point[0], point[1], point[0] + it.width, point[1] + it.height), rect)
    }
    fun isAttached() = border?.isAttachedToWindow == true
    internal fun emergencyStopBounds() = xStopOverlay.screenBounds()
    fun emergencyXAttached() = xStopOverlay.isAttached()
    /** Modern task UI owns the emergency X while it is visible. */
    fun hideEmergencyXOnly() { xStopOverlay.hide() }
    /** Rebuild a missing control panel without changing the user-owned session state. */
    fun restorePanel(showEmergencyX: Boolean = true) {
        if (session.state == ControlSession.State.PAUSED && unlocked()) {
            if (border == null || !isAttached()) hide()
            show(showEmergencyX = showEmergencyX)
        }
    }
    private fun unlocked() = !service.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
        service.getSystemService(android.os.PowerManager::class.java).isInteractive
    private val guard = object : Runnable {
        override fun run() {
            if (!unlocked()) { hide(); return } // lock changes visibility, never session ownership
            placePanel(); border?.postDelayed(this, 500)
        }
    }
    fun show(testOnly: Boolean = false, showEmergencyX: Boolean = true) {
        if (session.state != ControlSession.State.ENDED) stoppedResult = false
        if (!unlocked() || (!testOnly && (session.state == ControlSession.State.IDLE ||
                session.state == ControlSession.State.ENDED && !stoppedResult))) { hide(); return }
        if (border == null) {
            try {
                val decoration = Border()
                add(decoration, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    Gravity.TOP or Gravity.START, 0, 0, touchable = false)
                border = decoration
                if (!testOnly && showEmergencyX) xStopOverlay.show()
                decoration.post(guard)
            } catch (_: Exception) { hide(); session.pause(); return }
        }
        if (!testOnly && !showEmergencyX) xStopOverlay.hide()
        if (!testOnly && showEmergencyX) xStopOverlay.show()
        // #128: Modern status island has sole ownership of the visible status
        // surface. Legacy controls retain emergency X and passive border only.
        // Do not create a second, non-draggable RemoteControlPanel window.
        removePanel()
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
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val metrics = service.resources.displayMetrics
        val nextWidth = panelWidth()
        val nextY = dp(108).coerceAtMost(
            (metrics.heightPixels - view.height - dp(24)).coerceAtLeast(0),
        )
        if (params.x != 0 || params.y != nextY || params.width != nextWidth) {
            params.x = 0
            params.y = nextY
            params.width = nextWidth
            if (view.isAttachedToWindow) runCatching { manager.updateViewLayout(view, params) }
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
        xStopOverlay.hide()
        border?.removeCallbacks(guard)
        windows.toList().forEach { runCatching { manager.removeViewImmediate(it) } }
        windows.clear(); border = null; panel = null
    }
    private inner class Border : PremiumControlDecoration(service, session)
    internal fun clearScreenshotEffects() { border?.clearEffects() }
    fun target(rect: Rect) { border?.target(rect) }
    fun action(rect: Rect, kind: String, completed: Boolean?) { border?.action(rect, kind, completed) }
    fun tap(rect: Rect, x: Float, y: Float) { border?.tap(rect, x, y) }
}
