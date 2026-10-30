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
        val metrics = event["metrics"]!!.jsonObject
        assertEquals("unverified", metrics["source_clock_contract"]!!.jsonPrimitive.content)
        org.junit.Assert.assertFalse(metrics.containsKey("first_clock_sample"))
        org.junit.Assert.assertFalse(metrics.containsKey("last_clock_sample"))
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
            assertEquals("0", metrics["uptime_fresh_elapsed_stale"]!!.jsonPrimitive.content)
            assertEquals("over_60s", metrics["first_age_bucket"]!!.jsonPrimitive.content)
            assertEquals("over_60s", metrics["last_age_bucket"]!!.jsonPrimitive.content)
            assertEquals("10_to_60s", metrics["clock_gap_bucket"]!!.jsonPrimitive.content)
            assertEquals("over_60s", metrics["last_uptime_age_bucket"]!!.jsonPrimitive.content)
            assertEquals("1", metrics["timestamp_same"]!!.jsonPrimitive.content)
            assertEquals("1", metrics["timestamp_increasing"]!!.jsonPrimitive.content)
            assertEquals("0", metrics["timestamp_decreasing"]!!.jsonPrimitive.content)
            assertEquals("unverified", metrics["source_clock_contract"]!!.jsonPrimitive.content)
            val first = metrics["first_clock_sample"]!!.jsonObject
            val last = metrics["last_clock_sample"]!!.jsonObject
            assertEquals("1000000000", first["image_timestamp_ns"]!!.jsonPrimitive.content)
            assertEquals("2000000000", last["image_timestamp_ns"]!!.jsonPrimitive.content)
            assertEquals("100000", last["received_elapsed_ms"]!!.jsonPrimitive.content)
            assertEquals("80000", last["received_uptime_ms"]!!.jsonPrimitive.content)
            // Diagnostics retain only the endpoints, even after multiple acquisitions.
            val sampleKeys = setOf("image_timestamp_ns", "acquire_started_elapsed_ms",
                "received_elapsed_ms", "received_uptime_ms", "sample_finished_elapsed_ms")
            assertEquals(sampleKeys, first.keys)
            assertEquals(sampleKeys, last.keys)
            assertEquals(setOf("first_clock_sample", "last_clock_sample"),
                metrics.keys.filter { it.endsWith("clock_sample") }.toSet())
            assertEquals("100000", first["acquire_started_elapsed_ms"]!!.jsonPrimitive.content)
            assertEquals("100000", last["sample_finished_elapsed_ms"]!!.jsonPrimitive.content)
            verify(exactly = 3) { image.close() }
            verify(exactly = 0) { image.planes }
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    @Test fun `uptime fresh timestamp remains refused when elapsed age is stale`() = runTest {
        val image = mockk<Image>(relaxed = true)
        mockkStatic(SystemClock::class)
        try {
            every { SystemClock.elapsedRealtime() } returns 100_000L
            every { SystemClock.uptimeMillis() } returns 90_000L
            every { image.timestamp } returns 89_500_000_000L
            every { reader.acquireLatestImage() } returns image
            every { handler.post(any<Runnable>()) } answers { firstArg<Runnable>().run(); true }
            every { handler.postDelayed(any<Runnable>(), 25L) } returns false

            val activeLease = lease()
            assertNull(activeLease.capture())
            val event = lastEvent()
            val metrics = event["metrics"]!!.jsonObject
            assertEquals("handler_post_rejected", event["reason_code"]!!.jsonPrimitive.content)
            assertEquals("stale_frame", metrics["last_observation"]!!.jsonPrimitive.content)
            assertEquals("1", metrics["stale_reads"]!!.jsonPrimitive.content)
            assertEquals("1", metrics["uptime_fresh_elapsed_stale"]!!.jsonPrimitive.content)
            assertEquals("10_to_60s", metrics["last_age_bucket"]!!.jsonPrimitive.content)
            assertEquals("0_to_1s", metrics["last_uptime_age_bucket"]!!.jsonPrimitive.content)
            assertEquals("unverified", metrics["source_clock_contract"]!!.jsonPrimitive.content)
            verify(exactly = 1) { image.close() }
            verify(exactly = 0) { image.planes }
            // The same source value must not acquire a fresh delivery lifetime.
            org.junit.Assert.assertFalse(activeLease.permitsDelivery(89_500L))
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    @Test fun `one elapsed sample classifies a frame at freshness boundary`() = runTest {
        val image = mockk<Image>(relaxed = true)
        mockkStatic(SystemClock::class)
        try {
            // The next clock read crosses the boundary. Geometry refusal proves
            // that initial timestamp checks consistently used the first sample.
            // Acquisition bracketing precedes the classification sample. The
            // diagnostic end sample crosses the boundary but must not reclassify.
            every { SystemClock.elapsedRealtime() } returnsMany listOf(100_000L, 100_000L, 100_001L)
            every { SystemClock.uptimeMillis() } returns 100_000L
            every { image.timestamp } returns 99_000_000_000L
            every { image.width } returns 0
            every { image.height } returns 1
            every { reader.acquireLatestImage() } returns image
            every { handler.post(any<Runnable>()) } answers { firstArg<Runnable>().run(); true }
            assertNull(lease().capture())
            assertEquals("geometry_refused", lastEvent()["reason_code"]!!.jsonPrimitive.content)
            val sample = lastEvent()["metrics"]!!.jsonObject["first_clock_sample"]!!.jsonObject
            assertEquals("100000", sample["acquire_started_elapsed_ms"]!!.jsonPrimitive.content)
            assertEquals("100000", sample["received_elapsed_ms"]!!.jsonPrimitive.content)
            assertEquals("100001", sample["sample_finished_elapsed_ms"]!!.jsonPrimitive.content)
            verify(exactly = 1) { image.close() }
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    @Test fun `new enqueue after drained baseline bypasses ambiguous source offset without relaxing geometry`() = runTest {
        val image = mockk<Image>(relaxed = true)
        var queued: Runnable? = null
        mockkStatic(SystemClock::class)
        try {
            every { SystemClock.elapsedRealtime() } returns 100_000L
            every { SystemClock.uptimeMillis() } returns 90_000L
            every { image.timestamp } returnsMany listOf(89_500_000_000L, 89_525_000_000L)
            every { image.width } returns 0 // independent geometry gate still refuses
            every { image.height } returns 1
            every { reader.acquireLatestImage() } returns image
            every { handler.post(any<Runnable>()) } answers { queued = firstArg(); true }
            every { handler.postDelayed(any<Runnable>(), 25L) } answers { queued = firstArg(); true }
            val capture = async { lease().capture() }
            runCurrent()
            checkNotNull(queued).run() // baseline rejected as stale
            checkNotNull(queued).run() // distinct newer frame, geometry still checked
            runCurrent()
            assertNull(capture.await())
            assertEquals("geometry_refused", lastEvent()["reason_code"]!!.jsonPrimitive.content)
            assertEquals("1", lastEvent()["metrics"]!!.jsonObject["stale_reads"]!!.jsonPrimitive.content)
            verify(exactly = 2) { image.close() }
            verify(exactly = 0) { image.planes }
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

