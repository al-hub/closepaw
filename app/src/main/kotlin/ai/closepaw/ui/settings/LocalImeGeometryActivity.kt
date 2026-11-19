package ai.closepaw.ui.settings

import ai.closepaw.chatgpt.control.LocalImeGeometryRead
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Explicit local UI. The sanitized report lives in this activity's memory
 * only. Android selection lets the user decide whether to copy it. */
internal class LocalImeGeometryActivity : ComponentActivity() {
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        fun label(value: String) = TextView(this).apply { text = value; content.addView(this) }
        label("삼성 키보드 구조 진단").textSize = 22f
        label("기존 화면 공유가 켜져 있어야 합니다. 1회 시작을 누른 뒤 45초 안에 Chrome의 빈 입력란에서 삼성 키보드를 띄워 주세요. 키를 누를 필요는 없습니다.")
        label("구조와 인증 픽셀 범위만 이 화면에 표시합니다. 입력값·추천 문구·이미지는 저장하거나 전송하지 않습니다.")
        val status = label("대기")
        val report = label("").apply { setTextIsSelectable(true) }
        val start = Button(this).apply { text = "읽기 전용 진단 1회 시작" }
        val cancel = Button(this).apply { text = "이번 진단 취소"; isEnabled = false }
        content.addView(start); content.addView(cancel)
        start.setOnClickListener {
            if (job?.isActive == true) return@setOnClickListener
            start.isEnabled = false; cancel.isEnabled = true
            status.text = "Chrome의 빈 입력란과 삼성 키보드를 기다리는 중입니다."
            report.text = ""
            job = lifecycleScope.launch {
                try {
                    val result = LocalImeGeometryRead.run()
                    status.text = if (result.geometry == null) "진단 중단 · ${result.reason}" else "구조 관찰 완료 · 기능 수용 시험 아님"
                    report.text = result.geometry?.toString().orEmpty()
                } catch (cancelled: CancellationException) {
                    status.text = "진단 취소됨"
                    throw cancelled
                } catch (_: Exception) {
                    status.text = "진단 중단 · local_diagnostic_failed"
                } finally {
                    start.isEnabled = true; cancel.isEnabled = false
                }
            }
        }
        cancel.setOnClickListener { job?.cancel() }
        setContentView(ScrollView(this).apply { addView(content) })
    }
}
