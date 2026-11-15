package ai.closepaw.qa

import ai.closepaw.app.AgentService
import ai.closepaw.chatgpt.control.ControlSession
import ai.closepaw.platform.virtualdisplay.ProjectionImageTransport
import ai.closepaw.security.ImageRevocationAuthority
import android.app.UiAutomation
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Manual Galaxy only. Same native acceptance engine as the no-ADB local UI.
 * Does not open fixtures, change policy, grant permissions, type or dispatch gestures. */
@RunWith(AndroidJUnit4::class)
class PhysicalScreenObservationTest {
    @Test fun captureCurrentBlankFixture() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("physical_screen_observation") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(CaptureQaAcceptance.CANDIDATE, context.packageName)
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ai.closepaw.onboarding.OnboardingStore(context).apply { migrateIfNeeded { false }; setCompleted() }
        val fixture = requireNotNull(args.getString("fixture_package"))
        require(fixture in CaptureQaAcceptance.fixtures)
        val scope = args.getString("capture_scope") ?: "screen_observe"
        require(scope in CaptureQaAcceptance.scopes && scope != "revoked")
        val waitMs = args.getString("physical_wait_ms")?.toLongOrNull() ?: 180_000L
        require(waitMs in 30_000L..600_000L)
        fun status(value: String) = instrumentation.sendStatus(1, Bundle().apply {
            putString("stream", "\nPHYSICAL_READY $value\n")
        })
        val deadline = SystemClock.elapsedRealtime() + waitMs
        var prepared = false
        var previous: String? = null
        var firstService: AgentService? = null
        var initialId: String? = null
        var initialEpoch: Long? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val service = AgentService.instance
            if (service != null) {
                if (firstService == null) {
                    firstService = service; initialId = service.remoteControlSession.id
                    initialEpoch = service.remoteControlSession.epoch
                } else {
                    assertSame("Service changed during setup; no automatic restart", firstService, service)
                    assertEquals("User control/session changed during setup", initialId, service.remoteControlSession.id)
                    assertEquals("User control/session changed during setup", initialEpoch, service.remoteControlSession.epoch)
                }
                assertTrue("Existing candidate session preserved", service.remoteControlSession.state in
                    setOf(ControlSession.State.IDLE, ControlSession.State.ENDED))
            }
            val setup = CaptureQaAcceptance.setup(context)
            val observation = ProjectionImageTransport.active()?.screenObservationAllowed() == true
            val next = "setup=$setup observation=$observation"
            if (previous != next) { status(next); previous = next }
            if (setup == "ready" && observation) { prepared = true; break }
            delay(500)
        }
        assertTrue("Setup deadline: $previous", prepared)
        val result = CaptureQaAcceptance.run(context, "adb", fixture, scope,
            (deadline - SystemClock.elapsedRealtime()).coerceIn(30_000L, 600_000L),
            args.getString("export_image") == "true", ::status)
        status("result=$result")
        assertEquals(result.toString(), if (scope == "screen_observe") "CAPTURE_PASS" else "REFUSAL_PASS",
            result.getValue("outcome").jsonPrimitive.content)
        if (args.getString("verify_stop_after_capture") == "true") {
            require(scope == "screen_observe")
            status("stop_sharing_using_system_indicator_then_keep_blank_fixture_visible")
            val stopDeadline = SystemClock.elapsedRealtime() + 180_000L
            while ((ProjectionImageTransport.active() != null || ImageRevocationAuthority.isConsentGranted) &&
                SystemClock.elapsedRealtime() < stopDeadline) delay(500)
            assertNull("Stop existing sharing locally", ProjectionImageTransport.active())
            assertFalse(ImageRevocationAuthority.isConsentGranted)
            val stopped = CaptureQaAcceptance.run(context, "adb", fixture, "revoked", 180_000L, false, ::status)
            status("result=$stopped")
            assertEquals(stopped.toString(), "REFUSAL_PASS", stopped.getValue("outcome").jsonPrimitive.content)
        }
    }
}
