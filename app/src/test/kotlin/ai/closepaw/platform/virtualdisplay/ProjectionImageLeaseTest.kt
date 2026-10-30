package ai.closepaw.platform.virtualdisplay

import ai.closepaw.chatgpt.control.ControlDiagnostics
import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProjectionImageLeaseTest {
    private val handler = mockk<Handler>(relaxed = true)
    private val oldReader = mockk<ImageReader>()
    private val reader = mockk<ImageReader>()
    private val scope = mockk<MediaProjectionFrameScope>()
    private val policy = MediaProjectionFramePolicy()
    private val gate = MediaProjectionConsentGate()
    private val image = mockk<Image>(relaxed = true)
    private val bitmap = mockk<Bitmap>(relaxed = true)
    private lateinit var queue: ProjectionRequestQueue
    private var now = 100_000L
    private var queued: Runnable? = null

    private fun prepare(lowerBound: Long = 100_000L): ProjectionImageLease {
        mockkStatic(SystemClock::class, Bitmap::class)
        every { SystemClock.elapsedRealtime() } answers { now }
        every { SystemClock.uptimeMillis() } answers { now - 10_000L }
        every { scope.handler } returns handler
        every { scope.reader } returns oldReader
        every { scope.isOpen() } returns true
        queue = ProjectionRequestQueue(reader, lowerBound)
        every { scope.beginRequest() } returns queue
        every { scope.endRequest(any()) } just Runs
        every { reader.acquireLatestImage() } returns image
        every { handler.post(any<Runnable>()) } answers { queued = firstArg(); true }
        every { handler.postDelayed(any<Runnable>(), any()) } answers { queued = firstArg(); true }
        every { image.timestamp } returns 89_995_000_000L // actual observed clock-domain mismatch shape
        every { image.width } returns 1
        every { image.height } returns 1
        val plane = mockk<Image.Plane>()
        every { image.planes } returns arrayOf(plane)
        every { plane.buffer } returns ByteBuffer.allocate(4)
        every { plane.rowStride } returns 4
        every { plane.pixelStride } returns 4
        every { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) } returns bitmap
        check(policy.openSurface())
        gate.reset(); check(gate.onRequestStarted()); gate.onDecision(true, "test")
        check(gate.onConsumed("test"))
        return ProjectionImageLease(scope, policy, gate)
    }
    private fun cleanup() = unmockkStatic(SystemClock::class, Bitmap::class)
    private fun event() = ControlDiagnostics.snapshot()["events"]!!.jsonArray.last().jsonObject
    private fun reason() = event()["reason_code"]!!.jsonPrimitive.content

    @Test fun `normal new queue accepts uptime clock without relabelling receipt and expires at lower bound`() = runTest {
        val lease = prepare(99_950L)
        try {
            val capture = async { lease.capture("request123") }; runCurrent(); queued!!.run(); runCurrent()
            val frame = capture.await()!!
            assertEquals(99_950L, frame.capturedAt)
            assertTrue(lease.permitsDelivery(frame.capturedAt))
            now = 109_951L
            assertFalse(lease.permitsDelivery(frame.capturedAt))
            assertEquals("captured", reason())
            val metrics = event()["metrics"]!!.jsonObject
            assertEquals("unverified", metrics["source_clock_contract"]!!.jsonPrimitive.content)
            assertEquals("new_request_buffer_queue", metrics["freshness_proof"]!!.jsonPrimitive.content)
            verify(exactly = 0) { oldReader.acquireLatestImage() }
            verify(exactly = 1) { scope.endRequest(queue) }
            verify(exactly = 1) { image.close() }
        } finally { cleanup() }
    }
    @Test fun `pre suspend or old queue cannot become fresh on receipt even with increasing timestamp`() = runTest {
        val lease = prepare(90_000L)
        try {
            every { image.timestamp } returnsMany listOf(89_999_000_000L, 90_000_000_000L)
            val capture = async { lease.capture() }; runCurrent()
            repeat(2) { queued!!.run() }
            advanceTimeBy(1500); runCurrent()
            assertNull(capture.await()); assertEquals("timeout", reason())
            assertEquals("2", event()["metrics"]!!.jsonObject["stale_reads"]!!.jsonPrimitive.content)
            verify(exactly = 0) { image.planes }
            verify(exactly = 1) { scope.endRequest(queue) }
        } finally { cleanup() }
    }
    @Test fun `sleep after queue creation expires it without clock offset conversion`() = runTest {
        val lease = prepare()
        try {
            val capture = async { lease.capture() }; runCurrent()
            now += 60_000L
            queued!!.run(); advanceTimeBy(1500); runCurrent()
            assertNull(capture.await()); verify(exactly = 0) { image.planes }
        } finally { cleanup() }
    }
    @Test fun `elapsed clock regression refuses pixels`() = runTest {
        val lease = prepare(100_001L)
        try {
            val capture = async { lease.capture() }; runCurrent(); queued!!.run()
            advanceTimeBy(1500); runCurrent(); assertNull(capture.await())
            verify(exactly = 0) { image.planes }
        } finally { cleanup() }
    }
    @Test fun `unknown duplicate future or regressing source stamps never set expiry`() = runTest {
        for (stamp in listOf(0L, Long.MAX_VALUE, 1L, -1L)) {
            // Each fixture supplies a genuinely new queue; producer clock is irrelevant.
            val local = ProjectionImageLeaseTest()
            val lease = local.prepare()
            try {
                every { local.image.timestamp } returns stamp
                val capture = async { lease.capture() }; runCurrent(); local.queued!!.run(); runCurrent()
                assertEquals(100_000L, capture.await()!!.capturedAt)
            } finally { local.cleanup() }
        }
    }
    @Test fun `geometry refusal closes image without reading planes`() = runTest {
        val lease = prepare()
        try {
            every { image.width } returns 0
            val capture = async { lease.capture() }; runCurrent(); queued!!.run(); runCurrent()
            assertNull(capture.await()); assertEquals("geometry_refused", reason())
            verify(exactly = 0) { image.planes }; verify { image.close(); scope.endRequest(queue) }
        } finally { cleanup() }
    }
    @Test fun `revocation before acquire never creates request or reads old queue`() = runTest {
        val lease = prepare()
        try {
            gate.onRevoked()
            val capture = async { lease.capture() }; runCurrent(); queued!!.run(); runCurrent()
            assertNull(capture.await()); assertEquals("lease_not_active", reason())
            verify(exactly = 0) { scope.beginRequest() }; verify(exactly = 0) { reader.acquireLatestImage() }
        } finally { cleanup() }
    }
    @Test fun `session replacement and rotation invalidate acquired frame delivery`() = runTest {
        val lease = prepare()
        try {
            val capture = async { lease.capture() }; runCurrent(); queued!!.run(); runCurrent()
            val frame = capture.await()!!
            gate.reset(); assertFalse(lease.permitsDelivery(frame.capturedAt))
            policy.invalidate(); assertFalse(lease.permitsDelivery(frame.capturedAt))
        } finally { cleanup() }
    }
    @Test fun `post acquire expiry recycles bitmap`() = runTest {
        val lease = prepare()
        try {
            every { image.close() } answers { now += 1001L }
            val capture = async { lease.capture() }; runCurrent(); queued!!.run(); runCurrent()
            assertNull(capture.await()); assertEquals("post_acquire_not_fresh", reason())
            verify(exactly = 1) { bitmap.recycle() }
        } finally { cleanup() }
    }
    @Test fun `cancelled pending acquisition closes request and removes callback`() = runTest {
        val lease = prepare()
        try {
            every { reader.acquireLatestImage() } returns null
            val capture = async { lease.capture() }; runCurrent(); queued!!.run()
            advanceTimeBy(1500); runCurrent()
            assertNull(capture.await()); assertEquals("timeout", reason())
            verify(exactly = 1) { scope.endRequest(queue) }; verify { handler.removeCallbacks(any<Runnable>()) }
        } finally { cleanup() }
    }
    @Test fun `frame resumed at deadline is reclaimed when timeout wins`() = runTest {
        val lease = prepare()
        try {
            val capture = async { lease.capture() }; runCurrent()
            advanceTimeBy(1500); queued!!.run(); runCurrent()
            assertNull(capture.await()); verify(exactly = 1) { bitmap.recycle() }
            verify(exactly = 1) { scope.endRequest(queue) }
        } finally { cleanup() }
    }
    @Test fun `reader error has no legacy fallback and closes request`() = runTest {
        val lease = prepare()
        try {
            every { reader.acquireLatestImage() } throws IllegalStateException()
            val capture = async { lease.capture() }; runCurrent(); queued!!.run(); runCurrent()
            assertNull(capture.await()); assertEquals("acquire_exception", reason())
            verify(exactly = 0) { oldReader.acquireLatestImage() }; verify { scope.endRequest(queue) }
        } finally { cleanup() }
    }
    @Test fun `concurrent capture cannot replace first request sink`() = runTest {
        val lease = prepare()
        try {
            val first = async { lease.capture() }; runCurrent()
            assertNull(lease.capture())
            queued!!.run(); runCurrent(); assertNotNull(first.await())
            verify(exactly = 1) { scope.beginRequest() }
        } finally { cleanup() }
    }
    @Test fun `tuned poll and timeout are applied to actual acquisition loop`() = runTest {
        val lease = prepare()
        mockkObject(ProjectionAcquisitionTuning)
        try {
            every { ProjectionAcquisitionTuning.snapshot() } returns ProjectionTuningValues(4, 3000, 100)
            every { reader.acquireLatestImage() } returns null
            val capture = async { lease.capture() }; runCurrent(); queued!!.run()
            verify { handler.postDelayed(any<Runnable>(), 100L) }
            advanceTimeBy(1500); runCurrent(); assertFalse(capture.isCompleted)
            advanceTimeBy(1500); runCurrent(); assertNull(capture.await())
            assertEquals("3000", event()["metrics"]!!.jsonObject["timeout_ms"]!!.jsonPrimitive.content)
            assertEquals("4", event()["metrics"]!!.jsonObject["tuning_revision"]!!.jsonPrimitive.content)
        } finally { unmockkObject(ProjectionAcquisitionTuning); cleanup() }
    }

}
