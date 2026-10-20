package ai.closepaw.app

import ai.closepaw.chatgpt.control.ControlSession
import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.*
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityManager
import kotlin.math.sin

/** Pixel-only overlay. Never receives input, stores text, or infers action success. */
internal open class PremiumControlDecoration(
    private val service: AccessibilityService,
    private val session: ControlSession,
) : View(service) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density get() = resources.displayMetrics.density
    private var target: RectF? = null
    private var targetAt = 0L
    private var effect: Effect? = null
    private val position = IntArray(2)
    private data class Effect(val bounds: RectF, val kind: String, val at: Long, val completed: Boolean?,
        val x: Float? = null, val y: Float? = null)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false; isFocusable = false
    }
    fun target(rect: Rect) {
        if (session.state != ControlSession.State.ACTIVE) return
        target = RectF(rect); targetAt = SystemClock.elapsedRealtime(); effect = null; invalidate()
    }
    /** `completed` is a native acknowledgement, never a verified external-app outcome. */
    fun action(rect: Rect, kind: String, completed: Boolean?) {
        if (session.state != ControlSession.State.ACTIVE) return
        target = null
        effect = Effect(RectF(rect), kind, SystemClock.elapsedRealtime(), completed); invalidate()
    }
    /** Exact injected screen point, emitted only after dispatchGesture was submitted. */
    fun tap(rect: Rect, x: Float, y: Float) {
        if (session.state != ControlSession.State.ACTIVE) return
        target = null
        effect = Effect(RectF(rect), "tap", SystemClock.elapsedRealtime(), null, x, y); invalidate()
    }
    fun clearEffects() { target = null; effect = null; invalidate() }
    override fun onDetachedFromWindow() { target = null; effect = null; super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Never keep geometry from the pre-rotation/fold screen.
        target = null; effect = null
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val interactive = service.getSystemService(PowerManager::class.java).isInteractive
        if (!interactive || session.state == ControlSession.State.ENDED) { target = null; effect = null; return }
        val userControlled = session.state != ControlSession.State.ACTIVE
        if (userControlled) { target = null; effect = null }
        val animate = PremiumMotionPolicy.animate(interactive, isAttachedToWindow,
            ValueAnimator.areAnimatorsEnabled(),
            service.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled,
            session.state in setOf(ControlSession.State.PAUSED, ControlSession.State.ENDED))
        val now = SystemClock.elapsedRealtime()
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val accent = when (session.state) {
            ControlSession.State.WAITING -> Color.rgb(225, 171, 83)
            ControlSession.State.PAUSED -> Color.rgb(139, 159, 178)
            ControlSession.State.REOBSERVING -> Color.rgb(141, 164, 239)
            else -> if (dark) Color.rgb(96, 212, 232) else Color.rgb(0, 113, 146)
        }
        val breath = if (animate) (.5 + .5 * sin(now / 1050.0)).toFloat() else .5f
        val edge = RectF(3 * density, 3 * density, width - 3 * density, height - 3 * density)
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.color = accent
        // Layered low-alpha strokes avoid expensive full-screen blur layers.
        for (layer in 3 downTo 1) {
            paint.strokeWidth = (2 + layer * 3) * density
            paint.alpha = (5 + 7 * breath).toInt()
            canvas.drawRoundRect(edge, 25 * density, 25 * density, paint)
        }
        paint.strokeWidth = 2 * density; paint.alpha = (115 + 45 * breath).toInt()
        canvas.drawRoundRect(edge, 25 * density, 25 * density, paint)
        getLocationOnScreen(position)
        target?.let { bounds ->
            val age = now - targetAt
            if (age >= PremiumMotionPolicy.TARGET_MS) target = null
            else drawTarget(canvas, local(bounds), accent, 1f, planned = true)
        }
        effect?.let { event ->
            val progress = PremiumMotionPolicy.progress(now, event.at, PremiumMotionPolicy.ACTION_MS)
            if (progress >= 1f) effect = null else {
                val rect = local(event.bounds)
                val tint = if (event.completed == false) Color.rgb(209, 139, 112) else accent
                val opacity = if (animate) PremiumMotionPolicy.fade(progress) else 1f
                drawTarget(canvas, rect, tint, opacity, planned = false)
                // A native node click/scroll has no known finger path. Only tap gets a point ripple.
                if (event.kind == "tap" && event.x != null && event.y != null) {
                    val x = event.x - position[0]; val y = event.y - position[1]
                    paint.shader = RadialGradient(x, y, 28 * density, intArrayOf(withAlpha(tint, (65 * opacity).toInt()), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                    paint.style = Paint.Style.FILL; paint.alpha = 255
                    canvas.drawCircle(x, y, 28 * density, paint); paint.shader = null
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * density
                    paint.color = tint; paint.alpha = (190 * opacity).toInt()
                    canvas.drawCircle(x, y, (if (animate) 9 + 28 * progress else 16f) * density, paint)
                }
                if (event.kind.startsWith("scroll")) drawDirection(canvas, rect, event.kind.endsWith("up"), tint, opacity)
            }
        }
        drawLabel(canvas, dark, accent)
        if (animate) postInvalidateDelayed(PremiumMotionPolicy.FRAME_MS)
        else {
            // Reduced motion gets one expiry invalidation, no 30fps static redraw loop.
            val targetRemaining = if (target != null) (targetAt + PremiumMotionPolicy.TARGET_MS - now).coerceAtLeast(1) else Long.MAX_VALUE
            val effectRemaining = effect?.let { (it.at + PremiumMotionPolicy.ACTION_MS - now).coerceAtLeast(1) } ?: Long.MAX_VALUE
            val remaining = minOf(targetRemaining, effectRemaining)
            if (remaining != Long.MAX_VALUE) postInvalidateDelayed(remaining)
        }
    }
    private fun local(rect: RectF) = RectF(rect).apply { offset(-position[0].toFloat(), -position[1].toFloat()) }
    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00ffffff) or (alpha.coerceIn(0, 255) shl 24)
    private fun drawTarget(canvas: Canvas, rect: RectF, color: Int, opacity: Float, planned: Boolean) {
        paint.shader = null; paint.color = color; paint.style = Paint.Style.FILL; paint.alpha = (18 * opacity).toInt()
        canvas.drawRoundRect(rect, 12 * density, 12 * density, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * density; paint.alpha = (170 * opacity).toInt()
        paint.pathEffect = if (planned) DashPathEffect(floatArrayOf(7 * density, 5 * density), 0f) else null
        canvas.drawRoundRect(rect, 12 * density, 12 * density, paint); paint.pathEffect = null
    }
    private fun drawDirection(canvas: Canvas, rect: RectF, up: Boolean, color: Int, opacity: Float) {
        val x = rect.centerX(); val y = rect.centerY(); val sign = if (up) -1 else 1
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * density; paint.strokeCap = Paint.Cap.ROUND
        paint.color = color; paint.alpha = (160 * opacity).toInt()
        canvas.drawLine(x, y - sign * 12 * density, x, y + sign * 12 * density, paint)
        canvas.drawLine(x, y + sign * 12 * density, x - 6 * density, y + sign * 6 * density, paint)
        canvas.drawLine(x, y + sign * 12 * density, x + 6 * density, y + sign * 6 * density, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }
    private fun drawLabel(canvas: Canvas, dark: Boolean, accent: Int) {
        paint.shader = null; paint.style = Paint.Style.FILL; paint.alpha = 255
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 14 * resources.displayMetrics.scaledDensity
        val prefix = if (target != null) "대상 확인 · " else ""
        val label = prefix + session.label
        val available = (width - 104 * density).coerceAtLeast(48 * density)
        val fitted = android.text.TextUtils.ellipsize(label, android.text.TextPaint(paint), available - 32 * density,
            android.text.TextUtils.TruncateAt.END).toString()
        val labelWidth = (paint.measureText(fitted) + 32 * density).coerceAtMost(available)
        val left = (width - labelWidth) / 2; val top = 94 * density
        val box = RectF(left, top, left + labelWidth, top + 42 * density)
        paint.color = if (dark) Color.rgb(18, 32, 45) else Color.rgb(246, 250, 252); paint.alpha = 245
        canvas.drawRoundRect(box, 21 * density, 21 * density, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density; paint.color = accent; paint.alpha = 100
        canvas.drawRoundRect(box, 21 * density, 21 * density, paint)
        paint.style = Paint.Style.FILL; paint.alpha = 255
        paint.color = if (dark) Color.rgb(235, 245, 250) else Color.rgb(22, 48, 64)
        val baseline = box.centerY() - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
        canvas.drawText(fitted, left + 16 * density, baseline, paint)
    }
}
