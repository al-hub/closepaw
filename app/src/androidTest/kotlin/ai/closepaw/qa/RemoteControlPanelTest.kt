package ai.closepaw.qa

import ai.closepaw.app.RemoteControlPanel
import ai.closepaw.chatgpt.control.ControlSession
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** On-device native island contract; physical accessibility overlay drag is separate QA. */
@RunWith(AndroidJUnit4::class)
class RemoteControlPanelTest {
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun status(view: View): TextView = (0 until (view as ViewGroup).childCount)
        .map { view.getChildAt(it) }
        .filterIsInstance<TextView>().single()

    @Test fun activeIslandContainsNoLegacyControlButtons() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val session = ControlSession()
            session.start()
            val panel = RemoteControlPanel(instrumentation.targetContext, session, {}, {}, { _, _ -> })
            assertTrue(buttons(panel).isEmpty())
            assertFalse(panel.isClickable)
            assertTrue(status(panel).text.toString().contains("AI 제어"))
            assertFalse(status(panel).isClickable)
            assertEquals(ControlSession.State.ACTIVE, session.state)
        }
    }

    @Test fun waitingRetainsStatusButNoPauseResumeStopOrDirectControl() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val session = ControlSession()
            session.start()
            session.waitForUser()
            val panel = RemoteControlPanel(instrumentation.targetContext, session, {}, {}, { _, _ -> })
            assertTrue(buttons(panel).isEmpty())
            assertTrue(status(panel).text.toString().contains("사용자"))
        }
    }

    @Test fun islandStatusUpdatesWithoutRecreatingTheView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val session = ControlSession(); session.start()
            val panel = RemoteControlPanel(instrumentation.targetContext, session, {}, {}, { _, _ -> })
            val before = status(panel)
            session.feedback("화면 확인 중")
            panel.update()
            assertSame(before, status(panel))
            assertEquals("화면 확인 중", before.text.toString())
            assertTrue(before.contentDescription.toString().contains("AI 상태"))
            session.end()
            panel.update()
            assertEquals("AI 제어 종료", before.text.toString())
        }
    }

    @Test fun smallViewportDoesNotRevealHiddenControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext
            val config = android.content.res.Configuration(base.resources.configuration).apply { fontScale = 2f }
            val panel = RemoteControlPanel(base.createConfigurationContext(config),
                ControlSession().apply { start() }, {}, {}, { _, _ -> })
            panel.fitAvailableHeight((80 * panel.resources.displayMetrics.density).toInt())
            assertTrue(buttons(panel).isEmpty())
            assertEquals(1, panel.childCount)
        }
    }
}
