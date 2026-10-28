package ai.closepaw.chatgpt

import ai.closepaw.security.ImageRevocationAuthority
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    @Test fun ocrRevokedBetweenHandlerChecksRetainsCapturedEpochAndIsDeniedAtDelivery() {
        // Exercise every supported screenshot-text provenance marker without an image block.
        for (marker in listOf("text_source", "capture_source", "ocr_derived_content")) {
            ImageRevocationAuthority.grantConsent()
            val capturedEpoch = ImageRevocationAuthority.currentEpoch
            val secret = "SECRET_SCREENSHOT_OCR"
            val tool = ReadAppTool {
                ReadAppToolResult(
                    structured = buildJsonObject {
                        put("status", "succeeded")
                        put("content", secret)
                        when (marker) {
                            "text_source" -> put(marker, "on_device_ocr")
                            "capture_source" -> put(marker, "screenshot_ocr")
                            else -> put(marker, true)
                        }
                    },
                    imageEpoch = capturedEpoch,
                )
            }
            mockkObject(ImageRevocationAuthority)
            val response = try {
                every { ImageRevocationAuthority.isConsentStillValid(capturedEpoch) } answers {
                    val valid = callOriginal() as Boolean
                    // Stop Sharing immediately after the first check reads valid consent.
                    if (valid) ImageRevocationAuthority.revokeConsent()
                    valid
                }
                readResponse(tool)
            } finally {
                unmockkObject(ImageRevocationAuthority)
            }
            assertThat(response.imageEpoch).isEqualTo(capturedEpoch)
            assertThat(ImageRevocationAuthority.isConsentStillValid(capturedEpoch)).isFalse()
            val raw = ByteArrayOutputStream()
            write(response, raw)
            assertThat(raw.toString("UTF-8")).contains("HTTP/1.1 403")
            assertThat(raw.toString("UTF-8")).contains("image_consent_revoked")
            assertThat(raw.toString("UTF-8")).doesNotContain(secret)
        }
    }

    @Test fun ocrRevokedBeforeHandlerResponseStillCarriesCapturedEpoch() {
        ImageRevocationAuthority.grantConsent()
        val capturedEpoch = ImageRevocationAuthority.currentEpoch
        val response = readResponse(ReadAppTool {
            ImageRevocationAuthority.revokeConsent()
            ReadAppToolResult(buildJsonObject {
                put("status", "succeeded")
                put("text_source", "on_device_ocr")
                put("content", "SECRET_SCREENSHOT_OCR")
            }, imageEpoch = capturedEpoch)
        })
        assertThat(response.imageEpoch).isEqualTo(capturedEpoch)
        val raw = ByteArrayOutputStream()
        write(response, raw)
        assertThat(raw.toString("UTF-8")).contains("HTTP/1.1 403")
        assertThat(raw.toString("UTF-8")).doesNotContain("SECRET_SCREENSHOT_OCR")
    }

    @Test fun nonImageReadOnlyTextRemainsDeliverableAfterRevocation() {
        ImageRevocationAuthority.grantConsent()
        val capturedEpoch = ImageRevocationAuthority.currentEpoch
        val response = readResponse(ReadAppTool {
            ImageRevocationAuthority.revokeConsent()
            ReadAppToolResult(buildJsonObject {
                put("status", "succeeded")
                put("text_source", "accessibility")
                put("content", "READ_ONLY_ACCESSIBILITY_TEXT")
            }, imageBase64 = "REVOKED_PIXELS", imageMimeType = "image/jpeg", imageEpoch = capturedEpoch)
        })
        assertThat(response.imageEpoch).isNull()
        val raw = ByteArrayOutputStream()
        write(response, raw)
        assertThat(raw.toString("UTF-8")).contains("HTTP/1.1 200")
        assertThat(raw.toString("UTF-8")).contains("READ_ONLY_ACCESSIBILITY_TEXT")
        assertThat(raw.toString("UTF-8")).doesNotContain("REVOKED_PIXELS")
    }

    private fun readResponse(tool: ReadAppTool): McpHttpResponse = McpJsonRpcHandler(
        ClosePawStatusTool("0.1.0", 1), readAppTool = tool,
    ).handle(
        """{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"read_app","arguments":{"app":"samsung_internet"}}}"""
    )

    @Test fun stopSharingAfterFirstFlushedImageChunkPreventsRemainingBody() {
        ImageRevocationAuthority.grantConsent()
        val epoch = ImageRevocationAuthority.currentEpoch
        val payload = "BEGIN_PIXELS" + "Q".repeat(64000) + "END_PIXELS"
        val raw = ByteArrayOutputStream()
        var writes = 0
        val sink = object : OutputStream() {
            override fun write(b: Int) { raw.write(b) }
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                raw.write(bytes, off, len)
                writes++
                // Header is the first flushed write. Stop after one image chunk.
                if (writes == 2) ImageRevocationAuthority.revokeConsent()
            }
        }
        write(McpHttpResponse(200, payload, imageEpoch = epoch), sink)
        val transmitted = raw.toString("UTF-8")
        assertThat(transmitted).contains("BEGIN_PIXELS")
        assertThat(transmitted).doesNotContain("END_PIXELS")
        assertThat(ImageRevocationAuthority.isConsentGranted).isFalse()
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
