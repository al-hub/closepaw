package ai.closepaw.qa

import ai.closepaw.app.AgentService
import ai.closepaw.app.ServiceOverlayController
import ai.closepaw.ui.overlay.compose.IslandOverlayHost
import android.app.UiAutomation
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Regression for Galaxy request (43,59) -> screen (43,126): use a real WM root. */
@RunWith(AndroidJUnit4::class)
class IslandScreenCoordinatesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun get(owner: Any, name: String): Any? = field(owner, name).get(owner)

    @Test fun persistedTopCoordinatesMatchPhysicalScreenWithoutSystemBarOffset() {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.executeShellCommand("settings put secure enabled_accessibility_services ai.closepaw/.app.AgentService").close()
        automation.executeShellCommand("settings put secure accessibility_enabled 1").close()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (AgentService.instance == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        val service = requireNotNull(AgentService.instance)
        lateinit var host: IslandOverlayHost
        var hostSet = false
        var savedX = -1; var savedY = -1
        try {
            main {
                val controller = get(service, "overlayController") as ServiceOverlayController
                host = get(controller, "statusIslandManager") as IslandOverlayHost
                hostSet = true
                savedX = field(host, "islandX").getInt(host); savedY = field(host, "islandY").getInt(host)
                service.remoteControlSession.end(); service.remoteControlSession.start()
                service.showRemoteControlActivity()
            }
            instrumentation.waitForIdleSync()
            // Exercise persisted-placement path twice: the old implementation adds the
            // status-bar origin each time, despite reporting LayoutParams.y == 59.
            repeat(2) {
                main {
                    host.hide()
                    field(host, "islandX").setInt(host, 43)
                    field(host, "islandY").setInt(host, 59)
                    host.show()
                }
                instrumentation.waitForIdleSync()
                main {
                    val actual = requireNotNull(host.observedBounds())
                    val root = get(requireNotNull(get(host, "composeHost")), "composeView") as View
                    val lp = root.layoutParams as WindowManager.LayoutParams
                    assertEquals("fit frame explicitly uses screen pixels", 0, lp.fitInsetsTypes)
                    assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, lp.layoutInDisplayCutoutMode)
                    assertEquals(43, actual.x); assertEquals(59, actual.y)
                    assertEquals(lp.x, actual.x); assertEquals(lp.y, actual.y)
                    assertTrue(actual.x + actual.width <= actual.displayWidth)
                    assertTrue(actual.y + actual.height <= actual.displayHeight)
                    val indicator = (get(service, "remoteControlIndicator\$delegate") as Lazy<*>).value!!
                    val stopHost = requireNotNull(get(indicator, "xStopOverlay"))
                    val stop = stopHost.javaClass.getDeclaredMethod("screenBounds").invoke(stopHost) as android.graphics.Rect?
                    assertNotNull("actual emergency X is attached", stop)
                    assertFalse(android.graphics.Rect.intersects(requireNotNull(stop),
                        android.graphics.Rect(actual.x, actual.y, actual.x + actual.width, actual.y + actual.height)))
                }
            }
        } finally {
            main {
                service.remoteControlSession.end(); service.hideRemoteControlActivity(force = true)
                if (hostSet) {
                    field(host, "islandX").setInt(host, savedX); field(host, "islandY").setInt(host, savedY)
                    service.getSharedPreferences("closepaw_island_position", 0).edit().putInt("x", savedX).putInt("y", savedY).apply()
                }
            }
        }
    }
}
