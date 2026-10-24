package ai.closepaw.qa

import ai.closepaw.app.AgentService
import ai.closepaw.chatgpt.control.*
import ai.closepaw.chatgpt.operations.*
import android.app.UiAutomation
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production operation -> native port -> owned window -> independent screen position. */
@RunWith(AndroidJUnit4::class)
class IslandOperationTest {
    @Test fun productionDragMovesTenTimesRejectsReplayAndPreservesStop() = runBlocking {
        val i = InstrumentationRegistry.getInstrumentation()
        val automation = i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.executeShellCommand("settings put secure enabled_accessibility_services ai.closepaw/.app.AgentService").close()
        automation.executeShellCommand("settings put secure accessibility_enabled 1").close()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (AgentService.instance == null && SystemClock.uptimeMillis() < deadline) delay(100)
        val service = requireNotNull(AgentService.instance)
        val port = AndroidControlPort(i.targetContext) { true }
        val registry = OperationRegistry()
        GenericControlOperations(GenericControl(port, { true }), port, Mutex()) { OperationReadiness() }.register(registry)
        suspend fun invoke(id: String, input: JsonObject) = registry.get(id)!!.handler.invoke(OperationInvocation("qa-${System.nanoTime()}", input))
        try {
            i.runOnMainSync { service.remoteControlSession.end(); service.remoteControlSession.start(); service.showRemoteControlActivity() }
            i.waitForIdleSync()
            val session = service.remoteControlSession.id
            val times = mutableListOf<Long>()
            repeat(10) {
                val observed = invoke(GenericControlOperations.ISLAND_OBSERVE, buildJsonObject { put("session_id", session) })
                assertTrue(observed.toString(), observed.success)
                val bounds = observed.data.getValue("bounds").jsonObject
                val y = bounds.getValue("y").jsonPrimitive.int
                val height = bounds.getValue("height").jsonPrimitive.int
                val screenHeight = bounds.getValue("display_height").jsonPrimitive.int
                val dy = if (it % 2 == 0 && y + height + 24 < screenHeight) 24 else -24
                val input = buildJsonObject {
                    put("session_id", session); put("observation_id", observed.data.getValue("observation_id"))
                    put("dx", 0); put("dy", dy); put("hold_ms", 450); put("move_ms", 120)
                }
                val start = SystemClock.elapsedRealtime()
                val moved = invoke(GenericControlOperations.ISLAND_DRAG, input)
                times.add(SystemClock.elapsedRealtime() - start)
                assertTrue(moved.toString(), moved.success)
                assertEquals(y + dy, moved.data.getValue("after").jsonObject.getValue("y").jsonPrimitive.int)
                assertFalse(invoke(GenericControlOperations.ISLAND_DRAG, input).success)
                assertEquals(ControlSession.State.ACTIVE, service.remoteControlSession.state)
            }
            val summary = requireNotNull(ControlLatencyStats.summarize(times))
            val file = java.io.File("/sdcard/Download/closepaw-premium-ux-qa/island-operation-timing.json")
            file.parentFile!!.mkdirs()
            file.writeText(buildJsonObject {
                put("environment", "Android35 emulator owned-window island operation; NOT Galaxy calculator/E2E benchmark")
                put("samples_ms", JsonArray(times.map { JsonPrimitive(it) })); put("count", summary.count)
                put("p50_ms", summary.p50Ms); put("p95_ms", summary.p95Ms); put("p99_ms", summary.p99Ms); put("mean_ms", summary.meanMs)
            }.toString())
            // Real emulator power/keyguard transitions; retain the session but render nothing while off.
            val epoch = service.remoteControlSession.epoch
            fun shell(command: String) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
            }
            val power = service.getSystemService(android.os.PowerManager::class.java)
            fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).run { isAccessible = true; get(owner) }
            val controller = requireNotNull(field(service, "overlayController"))
            val host = field(controller, "statusIslandManager") as ai.closepaw.ui.overlay.compose.IslandOverlayHost
            suspend fun awaitCondition(description: String, condition: suspend () -> Boolean) {
                val until = SystemClock.uptimeMillis() + 5_000
                // Keep sampling within the original deadline instead of checking once
                // to leave the loop and again to assert (a transient window reattach
                // could flip between those calls and fail before the deadline).
                // Two observations 50ms apart require stable restoration, not a flicker.
                var consecutive = 0
                while (SystemClock.uptimeMillis() < until) {
                    consecutive = if (condition()) consecutive + 1 else 0
                    if (consecutive >= 2) break
                    delay(50)
                }
                assertTrue(description, consecutive >= 2)
            }
            repeat(10) {
                shell("input keyevent 223")
                awaitCondition("emulator must really enter non-interactive state") { !power.isInteractive }
                awaitCondition("screen-off broadcast removes the actual island and X decoration") {
                    withContext(Dispatchers.Main) { !host.isShowing() && !service.isRemoteControlActivityAttached() }
                }
                assertNull("island hidden while display is off", port.observeIsland())
                assertEquals(session, service.remoteControlSession.id)
                assertEquals(epoch, service.remoteControlSession.epoch)
                shell("input keyevent 224")
                shell("wm dismiss-keyguard")
                awaitCondition("same session restores island after unlock") { port.observeIsland() != null }
                i.waitForIdleSync()
                assertEquals(session, service.remoteControlSession.id)
                assertEquals(epoch, service.remoteControlSession.epoch)
            }
            // X/session stop interrupts a hold before any displacement.
            val stopObserved = invoke(GenericControlOperations.ISLAND_OBSERVE, buildJsonObject { put("session_id", session) })
            val stopInput = buildJsonObject {
                put("session_id", session); put("observation_id", stopObserved.data.getValue("observation_id"))
                put("dx", 0); put("dy", 24); put("hold_ms", 1000); put("move_ms", 120)
            }
            val pending = launch { invoke(GenericControlOperations.ISLAND_DRAG, stopInput) }
            delay(150)
            i.runOnMainSync { service.remoteControlSession.end() }
            pending.join()
            assertEquals(ControlSession.State.ENDED, service.remoteControlSession.state)
            assertNull(port.observeIsland())
            // A new session here is a test fixture only, never production recovery.
            i.runOnMainSync { service.remoteControlSession.start(); service.showRemoteControlActivity() }
            i.waitForIdleSync()
            val newSession = service.remoteControlSession.id
            val last = invoke(GenericControlOperations.ISLAND_OBSERVE, buildJsonObject { put("session_id", newSession) })
            i.runOnMainSync { service.remoteControlSession.end() }
            i.waitForIdleSync()
            assertFalse(invoke(GenericControlOperations.ISLAND_DRAG, buildJsonObject {
                put("session_id", session); put("observation_id", last.data.getValue("observation_id"))
                put("dx", 0); put("dy", 24); put("hold_ms", 450); put("move_ms", 120)
            }).success)
            assertNull(port.observeIsland())
        } finally {
            i.runOnMainSync { service.remoteControlSession.end(); service.hideRemoteControlActivity(force = true) }
        }
    }
}
