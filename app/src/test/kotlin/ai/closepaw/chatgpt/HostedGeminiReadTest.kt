package ai.closepaw.chatgpt

import ai.closepaw.app.AgentService
import ai.closepaw.platform.AccessibilityPlatform
import ai.closepaw.platform.AccessibilityScreenshotCapturer
import ai.closepaw.platform.ActionResult
import android.view.accessibility.AccessibilityNodeInfo
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Exercises the actual read path; a host package alone must never permit capture. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostedGeminiReadTest {
    private val service = mockk<AgentService>(relaxed = true)
    private val root = mockk<AccessibilityNodeInfo>(relaxed = true)
    private val google = AndroidGeminiDraftUi.GOOGLE_HOST
    private var verified = true

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { service.remoteControlSession } returns ai.closepaw.chatgpt.control.ControlSession()
        mockkConstructor(AccessibilityPlatform::class, AndroidGeminiDraftUi::class, AccessibilityScreenshotCapturer::class)
        every { anyConstructed<AccessibilityPlatform>().hasRequiredPermissions() } returns true
        every { anyConstructed<AccessibilityPlatform>().getCurrentPackageName() } returns google
        coEvery { anyConstructed<AccessibilityPlatform>().launchApp(any()) } returns ActionResult.Success("launched")
        coEvery { anyConstructed<AndroidGeminiDraftUi>().isLocked() } returns false
        coEvery { anyConstructed<AndroidGeminiDraftUi>().isReadScreen() } returns false
        coEvery { anyConstructed<AndroidGeminiDraftUi>().inspect() } answers { DraftComposerState(verified, if (verified) 1 else 0, "") }
        every { anyConstructed<AndroidGeminiDraftUi>().evidence() } answers {
            DraftWindowEvidence(observedPackage = google, rootPackage = google, windowId = 7)
        }
        every { service.windows } returns emptyList()
        every { service.rootInActiveWindow } returns root
        every { root.packageName } returns google
        every { root.windowId } returns 7
        every { root.isVisibleToUser } returns true
        every { root.text } returns "A visible conversation paragraph long enough to read without screenshot OCR."
        coEvery { anyConstructed<AccessibilityScreenshotCapturer>().captureIfEnabled(any(), any()) } returns null
    }

    @After fun teardown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun read() = AndroidReadAppTool({ service }, 0, BrowserScreenshotOcr { error("no image") }).read("gemini")

    @Test fun userPauseOrStopPreventsDirectReadToolLaunchAndCapture() {
        for (ended in listOf(false, true)) {
            val session = ai.closepaw.chatgpt.control.ControlSession().apply { activateLegacy(); if (ended) end() else pause() }
            every { service.remoteControlSession } returns session
            assertThat(read().structured["status"]!!.jsonPrimitive.content).isEqualTo("failed")
        }
        coVerify(exactly = 0) { anyConstructed<AccessibilityPlatform>().launchApp(any()) }
        coVerify(exactly = 0) { anyConstructed<AccessibilityScreenshotCapturer>().captureIfEnabled(any(), any()) }
    }

    @Test fun verifiedGoogleHostedScreenReachesTheSameSingleCaptureReadPath() {
        val result = read().structured
        assertThat(result["status"]!!.jsonPrimitive.content).isEqualTo("succeeded")
        assertThat(result["package_name"]!!.jsonPrimitive.content).isEqualTo(google)
        assertThat(result["content"]!!.jsonPrimitive.content).contains("visible conversation paragraph")
        coVerify(exactly = 1) { anyConstructed<AccessibilityScreenshotCapturer>().captureIfEnabled(null, true) }
        coVerify(exactly = 0) { anyConstructed<AndroidGeminiDraftUi>().setIfEmpty(any()) }
    }

    @Test fun genericGoogleSearchNeverProducesTextOrScreenshot() {
        verified = false
        val result = read()
        assertThat(result.structured["reason_code"]!!.jsonPrimitive.content).isEqualTo("target_not_foreground")
        assertThat(result.structured["content"]).isNull()
        assertThat(result.imageBase64).isNull()
        coVerify(exactly = 0) { anyConstructed<AccessibilityScreenshotCapturer>().captureIfEnabled(any(), any()) }
        assertThat(result.structured["screenshot_attempts"]!!.jsonPrimitive.content).isEqualTo("0")
    }

    @Test fun hostLosingGeminiIdentityDuringCaptureDiscardsAllContent() {
        coEvery { anyConstructed<AccessibilityScreenshotCapturer>().captureIfEnabled(any(), any()) } answers {
            verified = false
            null
        }
        val result = read()
        assertThat(result.structured["reason_code"]!!.jsonPrimitive.content).isEqualTo("target_changed")
        assertThat(result.structured["content"]).isNull()
        assertThat(result.imageBase64).isNull()
        assertThat(result.structured["screenshot_attempts"]!!.jsonPrimitive.content).isEqualTo("1")
    }
    @Test fun positivelyVerifiedActiveFocusedGeminiRootDoesNotDependOnLaggingPackageEvent() {
        every { anyConstructed<AndroidGeminiDraftUi>().evidence() } returns DraftWindowEvidence(
            observedPackage = "com.android.systemui", rootPackage = google, windowId = 7,
            windowType = android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION,
            active = true, focused = true,
        )
        val result = read().structured
        assertThat(result["status"]!!.jsonPrimitive.content).isEqualTo("succeeded")
    }
    @Test fun verifiedNativeSidebarCanBeReadWithoutAComposerButDoesNotWrite() {
        verified = false
        coEvery { anyConstructed<AndroidGeminiDraftUi>().isReadScreen() } returns true
        val result = read().structured
        assertThat(result["status"]!!.jsonPrimitive.content).isEqualTo("succeeded")
        coVerify(exactly = 0) { anyConstructed<AndroidGeminiDraftUi>().setIfEmpty(any()) }
    }
    @Test fun unverifiedBackgroundRootCannotReplaceLaggingForegroundEvent() {
        every { anyConstructed<AndroidGeminiDraftUi>().evidence() } returns DraftWindowEvidence(
            observedPackage = "other.app", rootPackage = google, windowId = 7,
            windowType = android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION,
            active = false, focused = false,
        )
        assertThat(read().structured["reason_code"]!!.jsonPrimitive.content).isEqualTo("target_not_foreground")
    }
}
