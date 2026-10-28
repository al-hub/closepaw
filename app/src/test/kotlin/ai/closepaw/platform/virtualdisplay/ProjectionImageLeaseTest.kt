package ai.closepaw.platform.virtualdisplay

import ai.closepaw.chatgpt.control.ControlDiagnostics
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import io.mockk.every
import io.mockk.mockk
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
        assertEquals("acquire_exception", lastEvent()["reason_code"]!!.jsonPrimitive.content)
    }

    @Test fun `revoked lease does not read image`() = runTest {
        every { handler.post(any<Runnable>()) } answers { firstArg<Runnable>().run(); true }
        val lease = lease()
        gate.onRevoked()
        assertNull(lease.capture())
        verify(exactly = 0) { reader.acquireLatestImage() }
        assertEquals("lease_not_active", lastEvent()["reason_code"]!!.jsonPrimitive.content)
    }
}
