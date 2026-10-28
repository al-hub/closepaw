package ai.closepaw.chatgpt

import ai.closepaw.security.ImageRevocationAuthority
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class McpBlockedImageWriterTest {
    @After fun cleanup() { ImageRevocationAuthority.revokeConsent() }

    private fun server() = McpHttpServer(0, McpJsonRpcHandler(ClosePawStatusTool("test", 1)))

    @Test fun revokeReturnsWhileWriterWaitsAndOldResponseCannotResumeAfterRegrant() {
        val epoch = ImageRevocationAuthority.grantConsent()
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        var writes = 0
        val transport = object : McpResponseTransport {
            override fun write(bytes: ByteBuffer): Int {
                assertTrue(Thread.holdsLock(ImageRevocationAuthority))
                writes++
                return 0
            }
            override fun awaitWritable(timeoutMillis: Long) {
                assertFalse(Thread.holdsLock(ImageRevocationAuthority))
                waiting.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            }
            override fun close() {
                assertFalse(Thread.holdsLock(ImageRevocationAuthority))
                closed.set(true)
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit { server().writeResponse(transport, McpHttpResponse(200, "PIXELS", imageEpoch = epoch)) }
            assertTrue(waiting.await(1, TimeUnit.SECONDS))
            // Must finish while the writer is still stuck, not after release.
            executor.submit<Long> { ImageRevocationAuthority.revokeConsent() }.get(1, TimeUnit.SECONDS)
            ImageRevocationAuthority.grantConsent()
            release.countDown()
            writer.get(1, TimeUnit.SECONDS)
            assertEquals(1, writes)
            assertTrue(closed.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun nonReadingSocketCannotBlockRevokeOrContinueImageWrites() {
        val epoch = ImageRevocationAuthority.grantConsent()
        val executor = Executors.newFixedThreadPool(2)
        ServerSocketChannel.open().use { listener ->
            listener.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            SocketChannel.open().use { peer ->
                peer.socket().receiveBufferSize = 1024
                peer.connect(listener.localAddress)
                listener.accept().use { channel ->
                    channel.socket().sendBufferSize = 1024
                    val actual = SocketMcpResponseTransport(channel)
                    val waiting = CountDownLatch(1)
                    val revoked = AtomicBoolean()
                    val wroteAfterRevoke = AtomicBoolean()
                    val transport = object : McpResponseTransport by actual {
                        override fun write(bytes: ByteBuffer): Int {
                            val count = actual.write(bytes)
                            if (revoked.get() && count > 0) wroteAfterRevoke.set(true)
                            return count
                        }
                        override fun awaitWritable(timeoutMillis: Long) {
                            waiting.countDown()
                            actual.awaitWritable(timeoutMillis)
                        }
                    }
                    try {
                        // Exceeds both socket buffers. The peer deliberately never reads.
                        val response = McpHttpResponse(200, "P".repeat(8 * 1024 * 1024), imageEpoch = epoch)
                        val writer = executor.submit { server().writeResponse(transport, response) }
                        assertTrue("writer never reached backpressure", waiting.await(2, TimeUnit.SECONDS))
                        executor.submit {
                            ImageRevocationAuthority.revokeConsent()
                            revoked.set(true)
                        }.get(1, TimeUnit.SECONDS)
                        writer.get(1, TimeUnit.SECONDS)
                        assertFalse(channel.isOpen)
                        assertFalse(wroteAfterRevoke.get())
                    } finally {
                        actual.close()
                        executor.shutdownNow()
                    }
                }
            }
        }
    }

    @Test fun stalledResponseTimesOutAndClosesWithoutAnotherWrite() {
        val epoch = ImageRevocationAuthority.grantConsent()
        var closed = false
        val transport = object : McpResponseTransport {
            override fun write(bytes: ByteBuffer) = 0
            override fun awaitWritable(timeoutMillis: Long) {
                assertFalse(Thread.holdsLock(ImageRevocationAuthority))
                Thread.sleep(timeoutMillis)
            }
            override fun close() { closed = true }
        }
        val started = System.nanoTime()
        assertThrows(SocketTimeoutException::class.java) {
            server().writeResponse(transport, McpHttpResponse(200, "PIXELS", imageEpoch = epoch), timeoutMillis = 50)
        }
        assertTrue(closed)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000)
    }
}
