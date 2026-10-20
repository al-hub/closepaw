package ai.closepaw.qa

import ai.closepaw.app.RemoteControlPanel
import ai.closepaw.chatgpt.control.ControlSession
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native View behavior checks; actual accessibility-overlay routing remains a device acceptance. */
@RunWith(AndroidJUnit4::class)
class RemoteControlPanelTest {
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }
    @Test fun waitingRetainsPauseAndSeparateResponseControl() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val session = ControlSession(); session.start(); session.waitForUser()
            var resume = 0
            val panel = RemoteControlPanel(instrumentation.targetContext, session, { resume++ }, { session.end() }, { _, _ -> })
            val pause = buttons(panel).single { it.text.toString() == "Ⅱ 일시정지" }
            assertTrue(buttons(panel).any { it.text.toString() == "✓ 응답 완료" })
            assertTrue(pause.minimumHeight >= (56 * panel.resources.displayMetrics.density).toInt())
            pause.performClick()
            assertEquals(ControlSession.State.PAUSED, session.state)
            assertEquals(0, resume)
            assertTrue(buttons(panel).any { it.text.toString() == "▶ 재개" })
        }
    }
    @Test fun manualTakeoverAndStopCannotLeaveAiActive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val session = ControlSession(); session.start()
            val panel = RemoteControlPanel(instrumentation.targetContext, session, {}, { session.end() }, { _, _ -> })
            buttons(panel).single { it.text.toString() == "직접 조작" }.performClick()
            assertEquals(ControlSession.State.PAUSED, session.state)
            buttons(panel).single { it.text.toString() == "■ 중단" }.performClick()
            assertEquals(ControlSession.State.ENDED, session.state)
            assertFalse(session.beginResume())
        }
    }
    @Test fun largeFontShortViewportKeepsOnlyBoundedEmergencyRow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext
            val config = android.content.res.Configuration(base.resources.configuration).apply { fontScale = 2f }
            val context = base.createConfigurationContext(config)
            val session = ControlSession(); session.start(); session.waitForUser()
            val panel = RemoteControlPanel(context, session, {}, { session.end() }, { _, _ -> })
            val density = context.resources.displayMetrics.density
            panel.fitAvailableHeight((100 * density).toInt())
            panel.measure(View.MeasureSpec.makeMeasureSpec((288 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertTrue("Emergency row must fit a 100dp available viewport", panel.measuredHeight <= 100 * density)
            assertTrue(buttons(panel).any { it.text.toString() == "Ⅱ 일시정지" && it.visibility == View.VISIBLE })
            assertTrue(buttons(panel).any { it.text.toString() == "■ 중단" && it.visibility == View.VISIBLE })
        }
    }
    @Test fun statusUpdateRetainsAccessibleControlInstances() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val session = ControlSession(); session.start()
            val panel = RemoteControlPanel(instrumentation.targetContext, session, {}, { session.end() }, { _, _ -> })
            val before = buttons(panel).single { it.text.toString() == "직접 조작" }
            session.feedback("화면 확인 중"); panel.update()
            assertSame(before, buttons(panel).single { it.text.toString() == "직접 조작" })
        }
    }
    @Test fun collapseKeepsEmergencyTaskControlsVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val session = ControlSession(); session.start()
            val panel = RemoteControlPanel(instrumentation.targetContext, session, {}, { session.end() }, { _, _ -> })
            buttons(panel).single { it.text.toString() == "⌃ 접기" }.performClick()
            assertEquals(View.VISIBLE, buttons(panel).single { it.text.toString() == "Ⅱ 일시정지" }.visibility)
            assertEquals(View.VISIBLE, buttons(panel).single { it.text.toString() == "■ 중단" }.visibility)
        }
    }
}
