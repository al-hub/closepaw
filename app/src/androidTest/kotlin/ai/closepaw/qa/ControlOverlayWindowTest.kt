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
    private fun indicatorFor(service: AgentService): Any =
        (field(service, "remoteControlIndicator\$delegate") as Lazy<*>).value!!
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

    @Test fun sameLocationRecoveryAndMeasuredRecreationNeverResumeEndedSession() {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.executeShellCommand("settings put secure enabled_accessibility_services ai.closepaw/.app.AgentService").close()
        automation.executeShellCommand("settings put secure accessibility_enabled 1").close()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (AgentService.instance == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        val service = requireNotNull(AgentService.instance)
        lateinit var controller: ServiceOverlayController
        lateinit var host: ai.closepaw.ui.overlay.compose.IslandOverlayHost
        try {
            main {
                service.remoteControlSession.end(); service.remoteControlSession.start()
                controller = field(service, "overlayController") as ServiceOverlayController
                host = field(controller, "statusIslandManager") as ai.closepaw.ui.overlay.compose.IslandOverlayHost
                controller.handleWindowStateChanged("com.android.settings", "com.android.settings.Settings", 0)
                service.showRemoteControlActivity()
            }
            instrumentation.waitForIdleSync()
            val epoch = service.remoteControlSession.epoch
            main {
                controller.hideAll() // Simulate a lock broadcast preceding the unlock window event.
                assertFalse(host.isShowing())
                controller.handleWindowStateChanged("com.android.settings", "com.android.settings.Settings", 0)
            }
            instrumentation.waitForIdleSync()
            main {
                val bounds = requireNotNull(host.observedBounds())
                assertTrue(bounds.x >= 0 && bounds.y >= 0)
                assertTrue(bounds.x + bounds.width <= bounds.displayWidth)
                assertTrue(bounds.y + bounds.height <= bounds.displayHeight)
                assertEquals(epoch, service.remoteControlSession.epoch)
                host.hide(); host.show()
            }
            instrumentation.waitForIdleSync()
            main {
                assertNotNull(host.observedBounds())
                service.stopRemoteAndLocalControl()
                controller.handleWindowStateChanged("com.android.settings", "com.android.settings.Settings", 0)
                assertEquals(ControlSession.State.ENDED, service.remoteControlSession.state)
                assertFalse(host.isShowing())
            }
        } finally { main { service.remoteControlSession.end(); service.hideRemoteControlActivity(force = true) } }
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
                // Draw the decoration alone: a second status capsule used to be painted
                // here even though WindowManager reported only one island window.
                val decoration = field(indicatorFor(service), "border") as View
                val bitmap = android.graphics.Bitmap.createBitmap(decoration.width, decoration.height,
                    android.graphics.Bitmap.Config.ARGB_8888)
                decoration.draw(android.graphics.Canvas(bitmap))
                val d = service.resources.displayMetrics.density
                val paintedCapsule = (94 * d).toInt() until (136 * d).toInt()
                for (row in paintedCapsule) {
                    assertEquals("decoration must not paint a second status island", 0,
                        android.graphics.Color.alpha(bitmap.getPixel(bitmap.width / 2, row)))
                }
                bitmap.recycle()
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
            var expectedX = 0
            var expectedY = 0
            main {
                expectedX = params(x).x
                expectedY = params(island).y
                assertEquals(expectedX, service.getSharedPreferences("x_stop_overlay", 0).getInt("x", -1))
                assertEquals(expectedY, service.getSharedPreferences("closepaw_island_position", 0).getInt("y", -1))
                service.remoteControlSession.feedback("화면 확인 중")
                controller.handleWindowStateChanged("com.android.settings", "com.android.settings.Settings", 0)
                controller.onTaskStarted("overlay-window-qa", "test-only state; no agent execution")
            }
            // addView registers a ViewRoot before its first traversal attaches the view.
            // Wait off Main before checking attachment or injecting the next gesture.
            instrumentation.waitForIdleSync()
            main {
                assertNull(view(remoteX))
                assertTrue("modern X must attach after traversal", requireNotNull(view(modernX)).isAttachedToWindow)
                assertSame(island, field(requireNotNull(field(islandHost, "composeHost")), "composeView"))
                controller.onSessionCompleted(SessionEndReason.USER_STOPPED)
                service.showRemoteControlActivity()
            }
            instrumentation.waitForIdleSync()
            main {
                x = requireNotNull(view(remoteX))
                assertNull(view(modernX))
                assertEquals(expectedX, params(x).x)
                assertEquals(expectedY, params(island).y)
            }
            var tapX = 0f; var tapY = 0f
            main { val xy = IntArray(2); x.getLocationOnScreen(xy); tapX = xy[0] + x.width / 2f; tapY = xy[1] + x.height / 2f }
            val tapAt = SystemClock.uptimeMillis()
            send(x, MotionEvent.ACTION_DOWN, tapAt, tapX, tapY)
            send(x, MotionEvent.ACTION_UP, tapAt, tapX, tapY)
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
