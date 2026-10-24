package ai.closepaw.chatgpt.operations

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class OperationHashTest {
    @Test fun optimizedEncodingPreservesEveryFingerprintByte() {
        val values = listOf("", "abc", "한글 🔒", "\u0000\u007f\u00ff") +
            (0..255).map { seed -> (0..seed).joinToString("") { ((it * 31 + seed) % 65536).toChar().toString() } }
        for (value in values) {
            val previous = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(previous, operationHash(value))
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", operationHash("abc"))
    }
}
