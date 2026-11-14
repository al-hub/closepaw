package ai.closepaw.qa

import ai.closepaw.app.AgentService
import ai.closepaw.chatgpt.McpExecutionSettings
import ai.closepaw.chatgpt.control.AndroidControlPort
import android.app.UiAutomation
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
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
        val service = requireNotNull(AgentService.instance) { "Enable candidate accessibility locally first" }
        val settings = McpExecutionSettings(service)
        assertTrue("Enable candidate generic control locally first", settings.genericControlEnabled)
        val port = AndroidControlPort(service, runtime = settings.runtimeControl) { settings.genericControlEnabled }
        val session = service.remoteControlSession
        assertTrue("End an old candidate control session before physical acceptance", session.state !=
            ai.closepaw.chatgpt.control.ControlSession.State.ACTIVE)
        try {
            instrumentation.runOnMainSync { assertTrue(session.start()) }
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
            val output = File(service.cacheDir, "screen-observation-physical").apply { mkdirs() }
            File(output, "result.json").writeText(buildJsonObject {
                put("fixture_package", expected)
                put("source_commit", ai.closepaw.BuildConfig.GIT_SHA)
                put("visual", proof.metadata())
                put("capture_succeeded", true)
                put("visual_actions_dispatched", 0)
            }.toString())
            if (args.getString("export_image") == "true") File(output, "preview.jpg").writeBytes(proof.image.bytes)
        } finally {
            instrumentation.runOnMainSync { session.end() }
        }
    }
}
