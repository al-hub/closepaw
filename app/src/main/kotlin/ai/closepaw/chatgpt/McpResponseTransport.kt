package ai.closepaw.chatgpt

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel

/** No application buffering: write must return immediately, including on backpressure. */
internal interface McpResponseTransport : Closeable {
    fun write(bytes: ByteBuffer): Int
    fun awaitWritable(timeoutMillis: Long)
}

internal class SocketMcpResponseTransport(private val channel: SocketChannel) : McpResponseTransport {
    private val selector = Selector.open()

    init {
        try {
            channel.configureBlocking(false)
            channel.register(selector, SelectionKey.OP_WRITE)
        } catch (failure: Exception) {
            selector.close()
            throw failure
        }
    }

    override fun write(bytes: ByteBuffer): Int = channel.write(bytes)

    override fun awaitWritable(timeoutMillis: Long) {
        selector.select(timeoutMillis)
        selector.selectedKeys().clear()
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            selector.close()
        }
    }
}
