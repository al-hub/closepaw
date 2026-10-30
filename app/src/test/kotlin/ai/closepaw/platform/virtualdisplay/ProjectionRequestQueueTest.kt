package ai.closepaw.platform.virtualdisplay

import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class ProjectionRequestQueueTest {
    @Test fun `new reader is attached and old identity cannot detach later request`() {
        mockkStatic(ImageReader::class, SystemClock::class)
        mockkConstructor(android.os.Handler::class)
        try {
            val old = mockk<ImageReader>(relaxed = true)
            val first = mockk<ImageReader>(relaxed = true)
            val second = mockk<ImageReader>(relaxed = true)
            val display = mockk<VirtualDisplay>(relaxed = true)
            val thread = mockk<HandlerThread>(relaxed = true)
            every { thread.looper } returns mockk<Looper>(relaxed = true)
            every { thread.isAlive } returns true
            every { old.width } returns 10; every { old.height } returns 20
            every { first.surface } returns mockk<Surface>()
            every { second.surface } returns mockk<Surface>()
            every { SystemClock.elapsedRealtime() } returnsMany listOf(100L, 200L)
            every { ImageReader.newInstance(10, 20, android.graphics.PixelFormat.RGBA_8888, 2) } returnsMany listOf(first, second)
            val scope = MediaProjectionFrameScope(old, display, thread)
            val q1 = scope.beginRequest()!!
            assertEquals(100L, q1.lowerBoundElapsedMs); assertSame(first, q1.reader)
            assertNull(scope.beginRequest())
            scope.endRequest(q1)
            val q2 = scope.beginRequest()!!
            scope.endRequest(q1) // late cleanup must not detach q2
            verify(exactly = 0) { second.close() }
            scope.close(); scope.close()
            assertNull(scope.beginRequest()); assertFalse(scope.isOpen())
            verify(exactly = 1) { first.close() }
            verify(exactly = 1) { second.close() }
            verify(exactly = 1) { display.release() }
            verify(exactly = 1) { old.close() }
            assertEquals(200L, q2.lowerBoundElapsedMs)
        } finally { unmockkStatic(ImageReader::class, SystemClock::class); unmockkConstructor(android.os.Handler::class) }
    }
    @Test fun `teardown cannot close reader while request pixels are being copied`() {
        val reader = mockk<ImageReader>(relaxed = true)
        val queue = ProjectionRequestQueue(reader, 100L)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val ended = java.util.concurrent.CountDownLatch(1)
        val copier = kotlin.concurrent.thread {
            synchronized(queue) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                check(!queue.closed)
            }
        }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val closer = kotlin.concurrent.thread { queue.close(); ended.countDown() }
        assertFalse(ended.await(50, java.util.concurrent.TimeUnit.MILLISECONDS))
        verify(exactly = 0) { reader.close() }
        release.countDown(); copier.join(5000); closer.join(5000)
        assertTrue(queue.closed)
        queue.close()
        verify(exactly = 1) { reader.close() }
    }
    @Test fun `partial setSurface failure closes fresh reader and permanently closes scope`() {
        mockkStatic(ImageReader::class, SystemClock::class)
        mockkConstructor(android.os.Handler::class)
        try {
            val old = mockk<ImageReader>(relaxed = true)
            val fresh = mockk<ImageReader>(relaxed = true)
            val display = mockk<VirtualDisplay>(relaxed = true)
            val thread = mockk<HandlerThread>(relaxed = true)
            every { thread.looper } returns mockk<Looper>(relaxed = true)
            every { thread.isAlive } returns true
            every { old.width } returns 1; every { old.height } returns 1
            every { ImageReader.newInstance(1, 1, android.graphics.PixelFormat.RGBA_8888, 2) } returns fresh
            every { display.surface = any() } throws IllegalStateException()
            val scope = MediaProjectionFrameScope(old, display, thread)
            assertNull(scope.beginRequest()); assertFalse(scope.isOpen())
            assertNull(scope.beginRequest())
            verify(exactly = 1) { fresh.close() }; verify { display.release(); old.close() }
        } finally { unmockkStatic(ImageReader::class, SystemClock::class); unmockkConstructor(android.os.Handler::class) }
    }
}
