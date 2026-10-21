package ai.closepaw.qa

import ai.closepaw.app.AgentService
import ai.closepaw.app.ServiceOverlayController
import ai.closepaw.chatgpt.control.ControlSession
import ai.closepaw.protocol.SessionEndReason
import android.app.UiAutomation
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real accessibility-service windows. Test-only direct event injection never ships as an operation. */
@RunWith(AndroidJUnit4::class)
class ControlOverlayWindowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).run {
        isAccessible = true; get(owner)
    }
    private fun view(host: Any): View? = field(host, "view") as? View
    private fun params(view: View) = view.layoutParams as WindowManager.LayoutParams
    private fun send(view: View, action: Int, down: Long, rawX: Float, rawY: Float) = main {
        val xy = IntArray(2); view.getLocationOnScreen(xy)
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rawX, rawY, 0)
        event.offsetLocation(-xy[0].toFloat(), -xy[1].toFloat())
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun drag(view: View, dx: Float, dy: Float) {
        var x = 0f; var y = 0f
        main { val xy = IntArray(2); view.getLocationOnScreen(xy); x = xy[0] + view.width / 2f; y = xy[1] + view.height / 2f }
        val down = SystemClock.uptimeMillis()
        send(view, MotionEvent.ACTION_DOWN, down, x, y)
        SystemClock.sleep(600)
        send(view, MotionEvent.ACTION_MOVE, down, x + dx, y + dy)
        SystemClock.sleep(80)
        send(view, MotionEvent.ACTION_UP, down, x + dx, y + dy)
        instrumentation.waitForIdleSync()
    }

    @Test fun realWindowsDragPersistArbitrateAndStopWithoutExposingRemoteMoveCommands() {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.executeShellCommand("settings put secure enabled_accessibility_services ai.closepaw/.app.AgentService").close()
        automation.executeShellCommand("settings put secure accessibility_enabled 1").close()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (AgentService.instance == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        val service = requireNotNull(AgentService.instance) { "accessibility service not connected" }
        lateinit var controller: ServiceOverlayController
        lateinit var islandHost: Any
        lateinit var remoteX: Any
        lateinit var modernX: Any
        lateinit var island: View
        lateinit var x: View
        try {
            main {
                controller = field(service, "overlayController") as ServiceOverlayController
                islandHost = requireNotNull(field(controller, "statusIslandManager"))
                val indicator = (field(service, "remoteControlIndicator\$delegate") as Lazy<*>).value!!
                remoteX = requireNotNull(field(indicator, "xStopOverlay"))
                modernX = requireNotNull(field(controller, "xStopOverlay"))
                service.remoteControlSession.start()
                service.showRemoteControlActivity()
            }
            instrumentation.waitForIdleSync()
            main {
                island = field(requireNotNull(field(islandHost, "composeHost")), "composeView") as View
                x = requireNotNull(view(remoteX))
                assertTrue(island.isAttachedToWindow)
                assertTrue(x.isAttachedToWindow)
                assertNull(view(modernX))
                assertEquals(0, params(island).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                assertNotEquals(0, params(island).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            }
            repeat(10) { index ->
                val delta = if (index % 2 == 0) 28f else -28f
                var beforeX = 0; var beforeY = 0
                main { beforeX = params(x).x; beforeY = params(island).y }
                drag(x, -delta, delta)
                drag(island, delta, delta)
                main {
                    assertNotEquals("X movement $index", beforeX, params(x).x)
                    assertNotEquals("island movement $index", beforeY, params(island).y)
                    assertEquals(ControlSession.State.ACTIVE, service.remoteControlSession.state)
                    assertTrue(x.isAttachedToWindow)
                }
            }
            main {
                val expectedX = params(x).x
                val expectedY = params(island).y
                assertEquals(expectedX, service.getSharedPreferences("x_stop_overlay", 0).getInt("x", -1))
                assertEquals(expectedY, service.getSharedPreferences("closepaw_island_position", 0).getInt("y", -1))
                service.remoteControlSession.feedback("화면 확인 중")
                controller.handleWindowStateChanged("com.android.settings", "com.android.settings.Settings", 0)
                controller.onTaskStarted("overlay-window-qa", "test-only state; no agent execution")
                assertNull(view(remoteX))
                assertTrue(requireNotNull(view(modernX)).isAttachedToWindow)
                assertSame(island, field(requireNotNull(field(islandHost, "composeHost")), "composeView"))
                controller.onSessionCompleted(SessionEndReason.USER_STOPPED)
                service.showRemoteControlActivity()
                x = requireNotNull(view(remoteX))
                assertNull(view(modernX))
                assertEquals(expectedX, params(x).x)
                assertEquals(expectedY, params(island).y)
                x.performClick()
            }
            instrumentation.waitForIdleSync()
            main {
                assertEquals(ControlSession.State.ENDED, service.remoteControlSession.state)
                assertNull(view(remoteX)); assertNull(view(modernX))
                assertNull(field(requireNotNull(field(islandHost, "composeHost")), "composeView"))
                assertEquals("control_session_ended", service.remoteControlSession.refusal())
            }
        } finally {
            main {
                service.remoteControlSession.end()
                (field(service, "overlayController") as? ServiceOverlayController)?.onSessionCompleted(SessionEndReason.USER_STOPPED)
                service.hideRemoteControlActivity(force = true)
            }
        }
    }
}
