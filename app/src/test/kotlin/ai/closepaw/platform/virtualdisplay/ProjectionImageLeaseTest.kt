package ai.closepaw.platform.virtualdisplay

import ai.closepaw.chatgpt.control.ControlDiagnostics
import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import android.media.ImageReader
import android.os.Handler
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProjectionImageLeaseTest {
    private val handler = mockk<Handler>(relaxed = true)
    private val reader = mockk<ImageReader>()
    private val scope = mockk<MediaProjectionFrameScope>()
    private val policy = MediaProjectionFramePolicy()
    private val gate = MediaProjectionConsentGate()

    private fun lease(): ProjectionImageLease {
        every { scope.handler } returns handler
        every { scope.reader } returns reader
        every { scope.isOpen() } returns true
        check(policy.openSurface())
        gate.reset()
        check(gate.onRequestStarted())
        gate.onDecision(true, "test-session")
        check(gate.onConsumed("test-session"))
        return ProjectionImageLease(scope, policy, gate)
    }

    private fun lastEvent() = ControlDiagnostics.snapshot()["events"]!!.jsonArray.last().jsonObject

    @Test fun `empty read deadline records timeout and observation separately`() = runTest {
        every { handler.post(any<Runnable>()) } answers { firstArg<Runnable>().run(); true }
        every { handler.postDelayed(any<Runnable>(), 25L) } returns true
        every { reader.acquireLatestImage() } returns null
        val capture = async { lease().capture() }
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertNull(capture.await())
        val event = lastEvent()
        assertEquals("timeout", event["reason_code"]!!.jsonPrimitive.content)
        assertEquals("no_frame_available", event["metrics"]!!.jsonObject["last_observation"]!!.jsonPrimitive.content)
        verify { handler.removeCallbacks(any<Runnable>()) }
    }

    @Test fun `stale frames stay refused and expose bounded timing categories`() = runTest {
        val image = mockk<Image>(relaxed = true)
        var queued: Runnable? = null
        mockkStatic(SystemClock::class)
        try {
            every { SystemClock.elapsedRealtime() } returns 100_000L
            every { SystemClock.uptimeMillis() } returns 80_000L
            every { image.timestamp } returnsMany listOf(1_000_000_000L, 1_000_000_000L, 2_000_000_000L)
            every { reader.acquireLatestImage() } returns image
            every { handler.post(any<Runnable>()) } answers { queued = firstArg(); true }
            every { handler.postDelayed(any<Runnable>(), 25L) } answers { queued = firstArg(); true }
            val capture = async { lease().capture() }
            runCurrent()
            repeat(3) { checkNotNull(queued).run() }
            advanceTimeBy(1_500)
            runCurrent()
            assertNull(capture.await())
            val event = lastEvent()
            val metrics = event["metrics"]!!.jsonObject
            assertEquals("timeout", event["reason_code"]!!.jsonPrimitive.content)
            assertEquals("stale_frame", metrics["last_observation"]!!.jsonPrimitive.content)
            assertEquals("3", metrics["stale_reads"]!!.jsonPrimitive.content)
            assertEquals("over_60s", metrics["first_age_bucket"]!!.jsonPrimitive.content)
            assertEquals("over_60s", metrics["last_age_bucket"]!!.jsonPrimitive.content)
            assertEquals("10_to_60s", metrics["clock_gap_bucket"]!!.jsonPrimitive.content)
            assertEquals("1", metrics["timestamp_same"]!!.jsonPrimitive.content)
            assertEquals("1", metrics["timestamp_increasing"]!!.jsonPrimitive.content)
            assertEquals("0", metrics["timestamp_decreasing"]!!.jsonPrimitive.content)
            verify(exactly = 3) { image.close() }
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    @Test fun `rejected retry reports handler failure`() = runTest {
        every { handler.post(any<Runnable>()) } answers { firstArg<Runnable>().run(); true }
        every { handler.postDelayed(any<Runnable>(), 25L) } returns false
        every { reader.acquireLatestImage() } returns null
        assertNull(lease().capture())
        assertEquals("handler_post_rejected", lastEvent()["reason_code"]!!.jsonPrimitive.content)
    }

    @Test fun `timestamp exception still closes acquired image`() = runTest {
        val image = mockk<Image>(relaxed = true)
        every { handler.post(any<Runnable>()) } answers { firstArg<Runnable>().run(); true }
        every { reader.acquireLatestImage() } returns image
        every { image.timestamp } throws IllegalStateException("test")
        assertNull(lease().capture())
        verify(exactly = 1) { image.close() }
        assertEquals("image_metadata_exception", lastEvent()["reason_code"]!!.jsonPrimitive.content)
    }

    @Test fun `revoked lease does not read image`() = runTest {
        every { handler.post(any<Runnable>()) } answers { firstArg<Runnable>().run(); true }
        val lease = lease()
        gate.onRevoked()
        assertNull(lease.capture())
        verify(exactly = 0) { reader.acquireLatestImage() }
        assertEquals("lease_not_active", lastEvent()["reason_code"]!!.jsonPrimitive.content)
    }
    @Test fun `frame resumed at deadline is reclaimed when timeout wins`() = runTest {
        val image = mockk<Image>(relaxed = true)
        val plane = mockk<Image.Plane>()
        val bitmap = mockk<Bitmap>(relaxed = true)
        var queued: Runnable? = null
        mockkStatic(SystemClock::class, Bitmap::class)
        try {
            every { SystemClock.elapsedRealtime() } returns 1000L
            every { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) } returns bitmap
            every { handler.post(any<Runnable>()) } answers { queued = firstArg(); true }
            every { reader.acquireLatestImage() } returns image
            every { image.timestamp } returns 1_000_000_000L
            every { image.width } returns 1
            every { image.height } returns 1
            every { image.planes } returns arrayOf(plane)
            every { plane.buffer } returns ByteBuffer.allocate(4)
            every { plane.rowStride } returns 4
            every { plane.pixelStride } returns 4
            val capture = async { lease().capture() }
            runCurrent()
            advanceTimeBy(1_500)
            checkNotNull(queued).run()
            runCurrent()
            assertNull(capture.await())
            verify(exactly = 1) { image.close() }
            verify(exactly = 1) { bitmap.recycle() }
        } finally {
            unmockkStatic(SystemClock::class, Bitmap::class)
        }
    }

    @Test fun `close failure overrides refusal before completion`() = runTest {
        val image = mockk<Image>(relaxed = true)
        every { handler.post(any<Runnable>()) } answers { firstArg<Runnable>().run(); true }
        every { reader.acquireLatestImage() } returns image
        every { image.timestamp } returns 0L
        every { image.close() } throws IllegalStateException("test")
        assertNull(lease().capture())
        verify(exactly = 1) { image.close() }
        assertEquals("image_close_exception", lastEvent()["reason_code"]!!.jsonPrimitive.content)
    }

    @Test fun `acquire exception is distinguished from metadata failure`() = runTest {
        every { handler.post(any<Runnable>()) } answers { firstArg<Runnable>().run(); true }
        every { reader.acquireLatestImage() } throws IllegalStateException("test")
        assertNull(lease().capture())
        assertEquals("acquire_exception", lastEvent()["reason_code"]!!.jsonPrimitive.content)
    }

}

