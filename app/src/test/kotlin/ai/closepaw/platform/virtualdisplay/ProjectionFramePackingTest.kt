package ai.closepaw.platform.virtualdisplay

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ProjectionFramePackingTest {
    @Test fun copiesPixelsWithoutPaddingAndRespectsBufferPosition() {
        val input = ByteBuffer.wrap(byteArrayOf(99,99,99, 1,2,3,4,5,6,7,8, 99,99,99,99, 9,10,11,12,13,14,15,16))
        input.position(3)
        val packed = ProjectionFramePacking.pack(input, 2, 2, 12, 4)!!
        assertArrayEquals(ByteArray(16) { (it + 1).toByte() }, packed.array())
        assertEquals(3, input.position())
        assertEquals(23, input.limit())
    }
    @Test fun refusesTruncatedBufferEvenWhenBackingArrayHasCapacity() {
        val input = ByteBuffer.allocate(24).apply { limit(15) }
        assertNull(ProjectionFramePacking.pack(input, 2, 2, 8, 4))
    }
    @Test fun refusesMalformedAndUnboundedLayouts() {
        val input = ByteBuffer.allocate(32)
        for ((width,height,stride,pixelStride) in listOf(
            listOf(0,2,8,4), listOf(2,0,8,4), listOf(2,2,4,4), listOf(2,2,9,4),
            listOf(2,2,8,3), listOf(Int.MAX_VALUE,2,8,4), listOf(1,1,40_000_004,4))) {
            assertNull(ProjectionFramePacking.pack(input,width,height,stride,pixelStride))
        }
    }
}
