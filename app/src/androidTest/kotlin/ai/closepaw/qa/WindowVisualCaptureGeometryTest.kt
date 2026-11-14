package ai.closepaw.qa

import ai.closepaw.chatgpt.control.IsolatedVisualSurface
import ai.closepaw.chatgpt.control.TapBounds
import ai.closepaw.chatgpt.control.WindowVisualCapture
import ai.closepaw.platform.virtualdisplay.ProjectionBitmap
import ai.closepaw.platform.virtualdisplay.ProjectionImageLease
import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Region
import android.hardware.display.DisplayManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.*
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic buffers only: no live screen, accessibility connection or projection permission.
 * These are refusal regressions, NOT Samsung coordinate calibration or an OEM fix. */
@RunWith(AndroidJUnit4::class)
class WindowVisualCaptureGeometryTest {
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
