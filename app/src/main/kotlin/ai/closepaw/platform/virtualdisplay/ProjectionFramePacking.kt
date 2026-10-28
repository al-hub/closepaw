package ai.closepaw.platform.virtualdisplay

import java.nio.ByteBuffer

/** Copies RGBA pixels only, respecting the buffer's position/limit and excluding row padding.
 * The last row need not include padding. Malformed layouts fail before any allocation. */
internal object ProjectionFramePacking {
    fun pack(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteBuffer? {
        if (width <= 0 || height <= 0 || width.toLong() * height > 8_000_000 || pixelStride != 4 ||
            rowStride.toLong() < width.toLong() * 4 || rowStride % 4 != 0 || rowStride.toLong() * height > 40_000_000) return null
        val required = (height - 1).toLong() * rowStride + width * 4L
        if (required > buffer.remaining()) return null
        val input = buffer.duplicate()
        val start = input.position(); val limit = input.limit()
        val packed = ByteBuffer.allocate(width * height * 4)
        for (row in 0 until height) {
            input.limit(limit)
            input.position(start + row * rowStride)
            input.limit(input.position() + width * 4)
            packed.put(input)
        }
        packed.flip()
        return packed
    }
}
