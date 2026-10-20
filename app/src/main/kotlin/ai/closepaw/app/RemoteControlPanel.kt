package ai.closepaw.app

import ai.closepaw.chatgpt.control.ControlSession
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Read-only control-state island. Emergency stop is provided exclusively by the
 * independently movable XStopOverlayHost, never by a second button here.
 */
internal class RemoteControlPanel(
    context: Context,
    private val session: ControlSession,
    @Suppress("UNUSED_PARAMETER") resume: () -> Unit,
    @Suppress("UNUSED_PARAMETER") stop: () -> Unit,
    @Suppress("UNUSED_PARAMETER") moved: (Float, Float) -> Unit,
) : LinearLayout(context) {
    private val dp get() = resources.displayMetrics.density
    private val dark = resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val status = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = (12 * dp).toInt()
        setPadding(pad, (8 * dp).toInt(), pad, (8 * dp).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 22 * dp
            setColor(if (dark) Color.rgb(20, 43, 60) else Color.rgb(235, 247, 253))
            setStroke((1 * dp).toInt().coerceAtLeast(1),
                if (dark) Color.rgb(100, 152, 169) else Color.rgb(114, 155, 173))
        }
        elevation = 4 * dp
        status.apply {
            textSize = 14f
            setTextColor(if (dark) Color.WHITE else Color.rgb(15, 47, 65))
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            minHeight = (32 * dp).toInt()
            gravity = Gravity.CENTER_VERTICAL
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            isClickable = false
            isFocusable = false
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        isClickable = false
        isFocusable = false
        update()
    }

    /** No optional buttons exist to collapse when the viewport gets shorter. */
    fun fitAvailableHeight(@Suppress("UNUSED_PARAMETER") height: Int): Boolean = false

    fun update(stopped: Boolean = session.state == ControlSession.State.ENDED) {
        val label = if (stopped) "AI 제어 종료" else session.label
        if (status.text.toString() != label) status.text = label
        status.contentDescription = "AI 상태: $label"
    }
}
