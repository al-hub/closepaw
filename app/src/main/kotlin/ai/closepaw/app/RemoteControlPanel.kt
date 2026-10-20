package ai.closepaw.app

import ai.closepaw.chatgpt.control.ControlSession
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Only this compact window receives input. No screen content or command text is retained. */
internal class RemoteControlPanel(context: Context, private val session: ControlSession,
    private val resume: () -> Unit, private val stop: () -> Unit,
    private val moved: (Float, Float) -> Unit) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(n: Int) = (n * density).toInt()
    private val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink = if (dark) Color.rgb(235, 248, 255) else Color.rgb(15, 47, 65)
    private val fill = if (dark) Color.rgb(20, 43, 60) else Color.rgb(223, 242, 250)
    private val status = TextView(context)
    private val primary = Button(context)
    private val secondary = LinearLayout(context)
    private val header = LinearLayout(context)
    private val manual = Button(context)
    private val response = Button(context)
    private var compact = false
    private var minimal = false
    private val fold = Button(context)
    private var folded = false
    private var startX = 0f
    private var startY = 0f
    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(12))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (dark) intArrayOf(Color.rgb(16, 36, 52), Color.rgb(9, 23, 37))
            else intArrayOf(Color.rgb(247, 253, 255), Color.rgb(229, 242, 249))).apply {
            cornerRadius = dp(24).toFloat(); setStroke(dp(1), if (dark) 0xFF4E849B.toInt() else 0xFF729BAD.toInt())
        }
        elevation = dp(12).toFloat()
        header.gravity = android.view.Gravity.CENTER_VERTICAL
        status.apply {
            textSize = 14f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(48); gravity = android.view.Gravity.CENTER_VERTICAL
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            contentDescription = "AI 상태 및 패널 이동 손잡이"
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; true }
                    MotionEvent.ACTION_MOVE -> {
                        moved(event.rawX - startX, event.rawY - startY)
                        startX = event.rawX; startY = event.rawY; true
                    }
                    MotionEvent.ACTION_UP -> { performClick(); true }
                    else -> false
                }
            }
        }
        header.addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        configure(fold, "⌃ 접기", "제어 패널 접기 또는 펼치기") {
            folded = !folded; update()
        }
        header.addView(fold, LayoutParams(dp(96), LayoutParams.WRAP_CONTENT))
        addView(header)
        val actions = LinearLayout(context)
        configure(primary, "Ⅱ 일시정지", "AI 제어 일시정지") {
            if (session.state == ControlSession.State.PAUSED) resume() else session.pause()
        }
        actions.addView(primary, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        val stopButton = Button(context)
        configure(stopButton, "■ 중단", "작업 중단, 자동 조작 차단") { stop() }
        actions.addView(stopButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        actions.tag = "actions"
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        secondary.orientation = VERTICAL
        addView(secondary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        configure(manual, "직접 조작", "AI를 멈추고 직접 조작") { session.manualTakeover() }
        secondary.addView(manual, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        configure(response, "✓ 응답 완료", "응답 완료 후 화면 확인") {
            if (session.userResponded()) resume()
        }
        secondary.addView(response, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        update()
    }
    /** Keep the emergency row within the visible area before adding optional controls. */
    fun fitAvailableHeight(height: Int): Boolean {
        val nextCompact = height < dp(320)
        val nextMinimal = height < dp(176)
        if (nextCompact == compact && nextMinimal == minimal) return false
        compact = nextCompact; minimal = nextMinimal
        setPadding(dp(12), dp(if (minimal) 4 else 10), dp(12), dp(if (minimal) 4 else 12))
        update(); return true
    }
    private fun configure(button: Button, label: String, description: String, action: () -> Unit) {
        button.apply {
            text = label; textSize = 15f; isAllCaps = false; minHeight = dp(56)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            minimumHeight = dp(56); minWidth = 0; minimumWidth = 0
            setPadding(dp(8), dp(8), dp(8), dp(8)); setTextColor(ink)
            background = RippleDrawable(ColorStateList.valueOf(0x3349BDD8), GradientDrawable().apply {
                setColor(fill); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0x665E9DB5)
            }, null)
            contentDescription = description; isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                action(); update()
            }
        }
    }
    fun update(stopped: Boolean = session.state == ControlSession.State.ENDED) {
        val nextStatus = if (stopped) "작업 중단됨\n자동 조작 차단" else session.label
        if (status.text.toString() != nextStatus) status.text = nextStatus
        status.contentDescription = "${status.text} · 패널 이동 손잡이"
        fold.text = if (folded) "⌄ 펼치기" else "⌃ 접기"
        primary.text = if (session.state == ControlSession.State.PAUSED) "▶ 재개" else "Ⅱ 일시정지"
        primary.contentDescription = if (session.state == ControlSession.State.PAUSED) "화면 확인 후 AI 제어 재개 · ${session.label}" else "AI 제어 일시정지 · ${session.label}"
        primary.isEnabled = session.state != ControlSession.State.REOBSERVING
        findViewWithTag<View>("actions").visibility = if (stopped) GONE else VISIBLE
        fold.visibility = if (stopped) GONE else VISIBLE
        header.visibility = if (minimal && !stopped) GONE else VISIBLE
        secondary.visibility = if (folded || stopped || compact) GONE else VISIBLE
        response.visibility = if (session.state == ControlSession.State.WAITING) VISIBLE else GONE
    }
}
