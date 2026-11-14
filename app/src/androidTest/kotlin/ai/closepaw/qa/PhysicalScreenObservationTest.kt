package ai.closepaw.qa

import ai.closepaw.app.AgentService
import ai.closepaw.chatgpt.McpExecutionSettings
import ai.closepaw.chatgpt.control.AndroidControlPort
import ai.closepaw.platform.virtualdisplay.ProjectionImageTransport
import android.app.UiAutomation
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Manual Galaxy acceptance only. Does not open apps, enable services, change policy,
 * grant projection consent, type, or dispatch gestures. Both fixtures must be blank.
 * Any explicitly exported image is temporary private test evidence, never CI output. */
@RunWith(AndroidJUnit4::class)
class PhysicalScreenObservationTest {
    @Test fun captureCurrentBlankFixture() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("physical_screen_observation") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("ai.closepaw.capturecandidate", instrumentation.targetContext.packageName)
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val expected = args.getString("fixture_package")
        require(expected in setOf("com.android.chrome", "com.samsung.android.app.notes"))
        // Standard instrumentation may restart the target. Never reuse a pre-run
        // projection assumption: wait for fresh local approval in this process.
        val deadline = SystemClock.elapsedRealtime() + 180_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            val current = AgentService.instance
            if (current != null && McpExecutionSettings(current).genericControlEnabled &&
                ProjectionImageTransport.active()?.screenObservationAllowed() == true) {
                ready = withContext(Dispatchers.Main) {
                    val root = current.rootInActiveWindow
                    val windows = current.windows.orEmpty()
                    try { root?.packageName?.toString() == expected && windows.any {
                        it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    } } finally {
                        root?.recycle()
                        windows.forEach { it.recycle() }
                    }
                }
            }
            if (!ready) delay(500)
        }
        assertTrue("Grant candidate local observation sharing, then show the blank fixture", ready)
        val service = requireNotNull(AgentService.instance) { "Enable candidate accessibility locally first" }
        val settings = McpExecutionSettings(service)
        assertTrue("Enable candidate generic control locally first", settings.genericControlEnabled)
        val port = AndroidControlPort(service, runtime = settings.runtimeControl) { settings.genericControlEnabled }
        val session = service.remoteControlSession
        val output = File(service.cacheDir, "screen-observation-physical").apply { mkdirs() }
        File(output, "result.json").delete()
        File(output, "preview.jpg").delete()
        assertTrue("End an old candidate control session before physical acceptance", session.state in setOf(
            ai.closepaw.chatgpt.control.ControlSession.State.IDLE, ai.closepaw.chatgpt.control.ControlSession.State.ENDED))
        var startedSessionId: String? = null
        try {
            instrumentation.runOnMainSync {
                assertTrue(session.start())
                startedSessionId = session.id
            }
            val frame = port.capture()
            assertNull("Native security/identity scan refused: ${frame.reason}", frame.reason)
            assertEquals(expected, frame.packageName)
            assertNotNull("Show Samsung keyboard on the blank fixture", frame.keyboard)
            assertEquals("com.samsung.android.honeyboard", frame.keyboard!!.packageName)
            val proof = requireNotNull(port.visualScreenObserve(frame)) { port.visualFailure() }
            assertEquals("screen_observe", proof.scope)
            assertEquals(0, proof.sourceDisplayId)
            assertTrue(proof.image.bytes.isNotEmpty())
            assertTrue(proof.sourceDisclosureAllowed())
            assertTrue(proof.disclosedRegions.isEmpty())
            assertNull(proof.map(ai.closepaw.chatgpt.control.TapBounds(0, 0, 1, 1)))
            File(output, "result.json").writeText(buildJsonObject {
                put("fixture_package", expected)
                put("source_commit", ai.closepaw.BuildConfig.GIT_SHA)
                put("visual", proof.metadata())
                put("capture_succeeded", true)
                put("visual_actions_dispatched", 0)
            }.toString())
            if (args.getString("export_image") == "true") File(output, "preview.jpg").writeBytes(proof.image.bytes)
        } finally {
            instrumentation.runOnMainSync {
                if (startedSessionId != null && session.id == startedSessionId) session.end()
            }
        }
    }
}
