package ai.closepaw.chatgpt

import ai.closepaw.security.ImageRevocationAuthority
import com.google.common.truth.Truth.assertThat
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.After
import org.junit.Test

class McpImageRevocationWriteTest {
    @After fun cleanup() { ImageRevocationAuthority.revokeConsent() }

    private fun write(response: McpHttpResponse, sink: OutputStream): String {
        val server = McpHttpServer(port = 0, handler = McpJsonRpcHandler(ClosePawStatusTool("0.1.0", 1)))
        val method = McpHttpServer::class.java.getDeclaredMethod(
            "writeResponse", BufferedOutputStream::class.java, McpHttpResponse::class.java)
        method.isAccessible = true
        val output = BufferedOutputStream(sink)
        method.invoke(server, output, response)
        return ""
    }

    @Test fun revokedEpochNeverStartsImageBody() {
        ImageRevocationAuthority.grantConsent()
        val epoch = ImageRevocationAuthority.currentEpoch
        val raw = ByteArrayOutputStream()
        ImageRevocationAuthority.revokeConsent()
        write(McpHttpResponse(200, "{\"type\":\"image\",\"data\":\"UNSAFEPIXELS\"}", imageEpoch = epoch), raw)
        val bytes = raw.toString("UTF-8")
        assertThat(bytes).doesNotContain("UNSAFEPIXELS")
        assertThat(bytes).contains("image_consent_revoked")
    }

    @Test fun revocationDuringChunkedImageWriteTruncatesResponse() {
        ImageRevocationAuthority.grantConsent()
        val epoch = ImageRevocationAuthority.currentEpoch
        val base64 = "A".repeat(64000)
        val raw = ByteArrayOutputStream()
        var calls = 0
        val sink = object : OutputStream() {
            override fun write(b: Int) { raw.write(b) }
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                raw.write(bytes, off, len)
                calls++
                if (calls == 1) ImageRevocationAuthority.revokeConsent()
            }
        }
        write(McpHttpResponse(200, base64, imageEpoch = epoch), sink)
        assertThat(raw.size()).isLessThan(base64.length)
        assertThat(ImageRevocationAuthority.isConsentGranted).isFalse()
    }
}
