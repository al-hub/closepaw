package ai.closepaw.qa

import ai.closepaw.app.MainActivity
import ai.closepaw.platform.virtualdisplay.MediaProjectionCaptureService
import ai.closepaw.platform.virtualdisplay.ProjectionConsentState
import ai.closepaw.platform.virtualdisplay.ProjectionImageTransport
import ai.closepaw.security.ImageRevocationAuthority
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import java.util.regex.Pattern

/**
 * Throwaway-emulator fixture only. Exercises MainActivity's real result callback,
 * foreground service, single-use OS token and mirror surface. No synthetic grants,
 * app-op overrides, injected frames, or production consent automation.
 *
 * Dialog selectors follow AOSP CTS MediaProjectionActivity (both API 35 dialog variants):
 * https://android.googlesource.com/platform/cts/+/refs/heads/main/tests/tests/media/common/src/android/media/cts/MediaProjectionActivity.java
 */
internal class OsProjectionConsentFixture {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val gate get() = MediaProjectionCaptureService.consentGate

    fun grant() = request(approve = true)
    fun deny() = request(approve = false)

    fun stop() {
        // Revoke synchronously, then wait for resource teardown before a new round.
        MediaProjectionCaptureService.revokeImageConsent()
        context.stopService(Intent(context, MediaProjectionCaptureService::class.java))
        instrumentation.waitForIdleSync()
        await("Projection lease remained active after stop") { ProjectionImageTransport.active() == null }
    }

    private fun request(approve: Boolean) {
        stop()
        assertNull(ProjectionImageTransport.active())
        assertFalse(ImageRevocationAuthority.isConsentGranted)
        // UiDevice must retain accessibility service binding during OS UI interaction.
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            activity.onActivity { it.requestProjectionConsent() }
            assertEquals(ProjectionConsentState.Requesting, gate.state)
            if (approve) {
                val options = requireNotNull(device.wait(Until.findObject(By.res(Pattern.compile(
                    "com.android.systemui:id/screen_share_mode_(spinner|options)"
                ))), 10_000)) { "OS projection sharing-mode control missing" }
                options.click()
                val resources = context.packageManager.getResourcesForApplication("com.android.systemui")
                val entireScreenId = resources.getIdentifier(
                    "screen_share_permission_dialog_option_entire_screen", "string", "com.android.systemui")
                assertTrue("OS entire-screen option resource missing", entireScreenId != 0)
                requireNotNull(device.wait(Until.findObject(By.text(resources.getString(entireScreenId))), 10_000)) {
                    "OS entire-screen option missing"
                }.click()
            }
            val button = if (approve) "android:id/button1" else "android:id/button2"
            requireNotNull(device.wait(Until.findObject(By.res(button).pkg("com.android.systemui")), 10_000)) {
                "OS projection consent button missing: $button"
            }.click()
            if (approve) {
                await("OS consent did not produce an active projection mirror") {
                    gate.state == ProjectionConsentState.Consumed &&
                        ProjectionImageTransport.active() != null && ImageRevocationAuthority.isConsentGranted
                }
            } else {
                await("OS denial did not close the consent request") { gate.state == ProjectionConsentState.Denied }
                assertNull(ProjectionImageTransport.active())
                assertFalse(ImageRevocationAuthority.isConsentGranted)
            }
        }
    }

    private fun await(message: String, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(message, check())
    }
}
