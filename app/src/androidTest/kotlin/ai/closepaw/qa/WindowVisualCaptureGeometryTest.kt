package ai.closepaw.qa

import ai.closepaw.app.AgentService
import ai.closepaw.chatgpt.control.AndroidControlPort
import ai.closepaw.chatgpt.control.GenericControl
import ai.closepaw.chatgpt.control.IsolatedVisualSurface
import ai.closepaw.chatgpt.control.RuntimeControlSettings
import ai.closepaw.chatgpt.control.VisualImageCache
import ai.closepaw.chatgpt.control.TapBounds
import ai.closepaw.chatgpt.control.WindowVisualCapture
import ai.closepaw.chatgpt.control.ImeSurfaceGeometry
import ai.closepaw.chatgpt.control.TermuxAdbImeGeometryAuthority
import ai.closepaw.bridge.TermuxJobClient
import ai.closepaw.bridge.TermuxJobReply
import ai.closepaw.bridge.TermuxJobSnapshot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import ai.closepaw.platform.virtualdisplay.ProjectionBitmap
import ai.closepaw.platform.virtualdisplay.ProjectionImageLease
import android.accessibilityservice.AccessibilityService
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Region
import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.*
import java.util.concurrent.Executor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic buffer regressions plus a real OS secure-window probe of a disposable fixture.
 * Neither stage grants MediaProjection or establishes Samsung physical-device acceptance. */
@RunWith(AndroidJUnit4::class)
class WindowVisualCaptureGeometryTest {
    @Test fun realSecureWindowRefusesScreenObservationBeforeImageAcquisition() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver = instrumentation.targetContext.contentResolver
        val enabledServices = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val accessibilityEnabled = Settings.Secure.ACCESSIBILITY_ENABLED
        val priorServices = Settings.Secure.getString(resolver, enabledServices)
        val priorEnabled = Settings.Secure.getString(resolver, accessibilityEnabled)
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        fun restore(key: String, value: String?) {
            if (value == null) shell("settings delete secure $key")
            else {
                val quoted = "'" + value.replace("'", "'\"'\"'") + "'"
                shell("settings put secure $key $quoted")
            }
        }
        suspend fun await(description: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (condition()) return
                delay(100)
            }
            fail(description)
        }
        var endSession: (() -> Unit)? = null
        try {
            shell("settings put secure $enabledServices ai.closepaw/.app.AgentService")
            shell("settings put secure $accessibilityEnabled 1")
            await("ClosePaw accessibility service never connected") { AgentService.instance != null }
            val service = requireNotNull(AgentService.instance)
            val session = service.remoteControlSession
            instrumentation.runOnMainSync { session.end(); assertTrue(session.start()) }
            endSession = { instrumentation.runOnMainSync { session.end() } }
            var record: String? = null
            val runtime = RuntimeControlSettings({ record }, { record = it })
            runtime.change(0, "apply", buildJsonObject {
                put("node_budget", 4096)
                put("output_limit", 50)
                put("apps", buildJsonObject { put("com.termux", "read") })
            })
            val port = AndroidControlPort(service, runtime = runtime) { true }
            val control = GenericControl(port, { true })

            suspend fun open(protectedWindow: Boolean, label: String) {
                val result = shell("am start -W -n com.termux/.ObservationWindowFixtureActivity " +
                    "--ez protected_window $protectedWindow")
                assertTrue(result, result.contains("Status: ok"))
                await("The requested fixture never became the active window: $label") {
                    var ready = false
                    instrumentation.runOnMainSync {
                        val root = service.rootInActiveWindow
                        if (root != null) try {
                            if (root.packageName?.toString() == "com.termux") {
                                val nodes = root.findAccessibilityNodeInfosByText(label)
                                try { ready = nodes.any { it.text?.toString() == label && it.isVisibleToUser } }
                                finally { nodes.forEach { it.recycle() } }
                            }
                        } finally { root.recycle() }
                    }
                    ready
                }
            }

            shell("am force-stop com.termux")
            open(false, "Public observation fixture")
            val public = control.observe()
            assertTrue(public.toString(), public.success)
            shell("am force-stop com.termux")
            open(true, "Protected observation fixture")
            val blocked = control.observe(includeImage = true, imageScope = "screen_observe")
            assertFalse(blocked.toString(), blocked.success)
            // This exact reason must come from Android's screenshot-of-window callback.
            // No projection, other refusal, or empty result can count as a pass.
            assertEquals(JsonPrimitive("secure_window"), blocked.data["reason_code"])
            assertFalse(blocked.data.containsKey("visual"))
            assertNull(VisualImageCache.imageFor(buildJsonObject { put("data", blocked.data) }))
        } finally {
            endSession?.invoke()
            shell("am force-stop com.termux")
            restore(enabledServices, priorServices)
            restore(accessibilityEnabled, priorEnabled)
        }
    }

    private class Surface(width: Int, height: Int) : AutoCloseable {
        private val hardware: Bitmap
        val buffer: android.hardware.HardwareBuffer
        val screenshot = mockk<AccessibilityService.ScreenshotResult>()
        init {
            val software = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            hardware = try { requireNotNull(software.copy(Bitmap.Config.HARDWARE, false)) }
                finally { software.recycle() }
            buffer = hardware.hardwareBuffer
            every { screenshot.hardwareBuffer } returns buffer
            every { screenshot.colorSpace } returns ColorSpace.get(ColorSpace.Named.SRGB)
        }
        override fun close() {
            buffer.close()
            hardware.recycle()
        }
    }

    private fun service(windowId: Int, surface: Surface): AccessibilityService {
        val service = mockk<AccessibilityService>()
        every { service.mainExecutor } returns Executor { it.run() }
        every { service.getSystemService(DisplayManager::class.java) } returns null
        every { service.takeScreenshotOfWindow(windowId, any(), any()) } answers {
            thirdArg<AccessibilityService.TakeScreenshotCallback>().onSuccess(surface.screenshot)
        }
        return service
    }

    @Test fun exactSyntheticWindowStillProducesAProof() = runBlocking {
        Surface(8, 12).use { surface ->
            val service = service(17, surface)
            val bounds = TapBounds(3, 7, 11, 19)
            val result = WindowVisualCapture.capture(service, 17, bounds, "synthetic")
            assertNull(result.reason)
            val proof = requireNotNull(result.proof)
            assertEquals(bounds, proof.window)
            assertEquals(8, proof.image.width)
            assertEquals(12, proof.image.height)
            assertTrue(proof.image.bytes.isNotEmpty())
            assertTrue(surface.buffer.isClosed)
            verify(exactly = 1) { service.takeScreenshotOfWindow(17, any(), any()) }
            verify(exactly = 0) { service.takeScreenshot(any(), any(), any()) }
        }
    }

    @Test fun portraitKeyboardMismatchReturnsNoProofAndNeverFallsBack() = runBlocking {
        keyboardRefusal(1440, 2993, TapBounds(0, 1798, 1440, 3120))
    }

    @Test fun certifiedTranslationRetainsNativeBufferAndMasksOutsideTouchableRegion() = runBlocking {
        Surface(200,300).use { surface ->
            val timestamp = android.os.SystemClock.uptimeMillis()
            every { surface.screenshot.timestamp } returns timestamp
            val touch = TapBounds(0,100,200,327)
            val geometry = ImeSurfaceGeometry(23,"android.os.BinderProxy@abc","VRI-InputMethod\$_123#456",
                TapBounds(0,27,200,327),touch,200,300,"fixture","a".repeat(64))
            val service = service(23,surface)
            val result = WindowVisualCapture.capture(service,23,touch,"native",
                geometry=geometry,minimumScreenshotUptimeMs=timestamp)
            assertNull(result.reason)
            val proof = requireNotNull(result.proof)
            assertEquals(200,proof.image.width); assertEquals(300,proof.image.height)
            assertEquals(geometry.frame,proof.window)
            assertEquals(listOf(touch),proof.disclosedRegions)
            assertEquals(TapBounds(20,107,40,127),proof.map(TapBounds(20,80,40,100)))
            assertNull(proof.map(TapBounds(20,20,40,40)))
            val decoded = BitmapFactory.decodeByteArray(proof.image.bytes,0,proof.image.bytes.size)
            try { assertEquals(Color.BLACK,decoded.getPixel(100,10)) } finally { decoded.recycle() }
            assertTrue(surface.buffer.isClosed)
            verify(exactly=0) { service.takeScreenshot(any(),any(),any()) }
        }
    }

    @Test fun certifiedSurfaceRejectsStaleScreenshotBeforeReturningAnyProof() = runBlocking {
        Surface(200,300).use { surface ->
            val timestamp = android.os.SystemClock.uptimeMillis()
            every { surface.screenshot.timestamp } returns timestamp - 1
            val touch = TapBounds(0,100,200,327)
            val geometry = ImeSurfaceGeometry(23,"android.os.BinderProxy@abc","VRI-InputMethod\$_123#456",
                TapBounds(0,27,200,327),touch,200,300,"fixture","a".repeat(64))
            val result = WindowVisualCapture.capture(service(23,surface),23,touch,"native",
                geometry=geometry,minimumScreenshotUptimeMs=timestamp)
            assertNull(result.proof); assertEquals("ime_capture_timestamp_unverified",result.reason)
            assertTrue(surface.buffer.isClosed)
        }
    }

    @Test fun adbAuthorityCannotReadAnotherDeviceOrReplayAnUnknownJob() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(requireNotNull(context.getExternalFilesDir(null)),"ime-geometry")
        fun files() = dir.listFiles()?.map { it.name }?.toSet().orEmpty()
        val baseline = files()
        val client = mockk<TermuxJobClient>()
        coEvery { client.start(any(),any()) } returns TermuxJobReply.Success(
            TermuxJobSnapshot("fixture","interrupted",outcomeUnknown=true))
        val authority = TermuxAdbImeGeometryAuthority(context,client) { true }
        val lease = requireNotNull(authority.lease(2,TapBounds(0,100,200,327),"ime/.Keyboard") { true })
        try {
            assertEquals(1,(files()-baseline).size)
            assertNull(lease.sample())
            coVerify(exactly=1) { client.start(any(),any()) }
            coVerify(exactly=0) { client.poll(any(),any(),any(),any()) }
        } finally { lease.close() }
        assertEquals(baseline,files())
        val disabled = TermuxAdbImeGeometryAuthority(context,client) { false }
        assertNull(disabled.lease(2,TapBounds(0,100,200,327),"ime/.Keyboard") { true })
        coVerify(exactly=1) { client.start(any(),any()) }
    }

    @Test fun landscapeKeyboardMismatchDoesNotReusePortraitInset() = runBlocking {
        // Only the historical sizes are known; (0,0) is a synthetic test origin.
        // This fixture does not assert the Samsung landscape Surface position.
        keyboardRefusal(2826, 1335, TapBounds(0, 0, 2826, 773))
    }

    private suspend fun keyboardRefusal(width: Int, height: Int, bounds: TapBounds) {
        Surface(width, height).use { surface ->
            val service = service(23, surface)
            val result = WindowVisualCapture.capture(service, 23, bounds, "synthetic")
            assertNull(result.proof)
            assertEquals("screenshot_buffer_geometry_mismatch", result.reason)
            assertTrue(surface.buffer.isClosed)
            verify(exactly = 1) { service.takeScreenshotOfWindow(23, any(), any()) }
            verify(exactly = 0) { service.takeScreenshot(any(), any(), any()) }
        }
    }

    @Test fun acquiredProjectionStillRefusesUnmappedIsolatedImeSurface() = runBlocking {
        val displayBounds = TapBounds(0, 0, 1440, 3120)
        val imeBounds = TapBounds(0, 1798, 1440, 3120)
        val bitmap = Bitmap.createBitmap(1440, 3120, Bitmap.Config.ARGB_8888)
        val projection = mockk<ProjectionImageLease>()
        coEvery { projection.capture("geometry236") } returns ProjectionBitmap(bitmap, projection, 1L)
        try {
            Surface(1440, 2993).use { surface ->
                val service = service(23, surface)
                var slots = 0
                val result = WindowVisualCapture.capture(service, 17, displayBounds, "synthetic",
                    display = true, visibleRegion = Region(0, 0, 1440, 3120),
                    surfaces = listOf(IsolatedVisualSurface(23, imeBounds, listOf(imeBounds))),
                    projection = projection, requestId = "geometry236", awaitSlot = { slots++ })
                assertNull(result.proof)
                assertEquals("display_isolated_screenshot_buffer_geometry_mismatch", result.reason)
                assertEquals(1, slots)
                assertTrue(surface.buffer.isClosed)
                assertTrue(bitmap.isRecycled)
                coVerify(exactly = 1) { projection.capture("geometry236") }
                verify(exactly = 1) { service.takeScreenshotOfWindow(23, any(), any()) }
                verify(exactly = 0) { service.takeScreenshot(any(), any(), any()) }
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Test fun explicitScreenObservationUsesProjectionCoordinatesAndNeverTheImeBuffer() = runBlocking {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        for (y in 100 until 200) for (x in 0 until 100) bitmap.setPixel(x, y, Color.RED)
        val projection = mockk<ProjectionImageLease>()
        every { projection.screenObservationAllowed() } returns true
        every { projection.permitsDelivery(1L) } returns true
        coEvery { projection.capture("screen236") } returns ProjectionBitmap(bitmap, projection, 1L)
        val service = mockk<AccessibilityService>()
        val bounds = TapBounds(0, 0, 100, 200)
        val result = WindowVisualCapture.capture(service, 17, bounds, "screen", display = true,
            visibleRegion = Region(0, 100, 100, 200),
            surfaces = listOf(IsolatedVisualSurface(23, TapBounds(0, 1798, 1440, 3120), emptyList())),
            projection = projection, requestId = "screen236", observationOnly = true)
        assertNull(result.reason)
        val proof = requireNotNull(result.proof)
        assertEquals("screen_observe", proof.scope)
        assertTrue(proof.disclosedRegions.isEmpty())
        assertNull(proof.map(TapBounds(10, 120, 20, 130)))
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(proof.image.bytes, 0, proof.image.bytes.size))
        try {
            assertEquals(100, decoded.width); assertEquals(200, decoded.height)
            assertTrue(Color.red(decoded.getPixel(50, 150)) > 240)
            assertTrue(Color.green(decoded.getPixel(50, 150)) < 15)
            assertTrue(Color.red(decoded.getPixel(50, 40)) < 15)
            assertTrue(Color.green(decoded.getPixel(50, 40)) < 15)
        } finally { decoded.recycle() }
        assertTrue(bitmap.isRecycled)
        verify(exactly = 0) { service.takeScreenshotOfWindow(any(), any(), any()) }
        verify(exactly = 0) { service.takeScreenshot(any(), any(), any()) }
    }

    @Test fun screenObservationWithoutSeparateConsentNeverAcquiresPixels() = runBlocking {
        val projection = mockk<ProjectionImageLease>()
        every { projection.screenObservationAllowed() } returns false
        val service = mockk<AccessibilityService>()
        val result = WindowVisualCapture.capture(service, 17, TapBounds(0, 0, 100, 200), "screen",
            display = true, visibleRegion = Region(0, 0, 100, 200), projection = projection, observationOnly = true)
        assertNull(result.proof)
        assertEquals("screen_observation_consent_required", result.reason)
        coVerify(exactly = 0) { projection.capture(any()) }
        verify(exactly = 0) { service.takeScreenshotOfWindow(any(), any(), any()) }
        verify(exactly = 0) { service.takeScreenshot(any(), any(), any()) }
    }

    @Test fun observationRevokedDuringCaptureWithholdsImageAndRecyclesFrame() = runBlocking {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        val projection = mockk<ProjectionImageLease>()
        every { projection.screenObservationAllowed() } returnsMany listOf(true, false)
        every { projection.permitsDelivery(1L) } returns true
        coEvery { projection.capture(any()) } returns ProjectionBitmap(bitmap, projection, 1L)
        val result = WindowVisualCapture.capture(mockk(), 17, TapBounds(0, 0, 100, 200), "screen",
            display = true, visibleRegion = Region(0, 0, 100, 200), projection = projection, observationOnly = true)
        assertNull(result.proof)
        assertEquals("projection_session_expired", result.reason)
        assertTrue(bitmap.isRecycled)
    }

    @Test fun observationDoesNotAcceptAnAppSizedProjectionAsDisplayGeometry() = runBlocking {
        val bitmap = Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888)
        val projection = mockk<ProjectionImageLease>()
        every { projection.screenObservationAllowed() } returns true
        coEvery { projection.capture(any()) } returns ProjectionBitmap(bitmap, projection, 1L)
        val service = mockk<AccessibilityService>()
        every { service.getSystemService(DisplayManager::class.java) } returns null
        val result = WindowVisualCapture.capture(service, 17, TapBounds(0, 0, 100, 200), "screen",
            display = true, visibleRegion = Region(0, 0, 100, 200), projection = projection, observationOnly = true)
        assertNull(result.proof)
        assertEquals("screenshot_buffer_geometry_mismatch", result.reason)
        assertTrue(bitmap.isRecycled)
    }
}
