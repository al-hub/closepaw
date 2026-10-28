package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.control.VisualImageCache
import ai.closepaw.chatgpt.control.VisualTargetProof
import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import com.google.common.truth.Truth.assertThat
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ai.closepaw.security.ImageRevocationAuthority
import org.junit.Before
import org.junit.After
import org.junit.Test

/**
 * JVM security/regression tests for MCP image response encoding behind issue #165.
 *
 * Contract under test: a cached proof is delivered exactly once per fresh lookup as a
 * well-formed MCP `image` content part whose base64 payload decodes to the original
 * bytes; after revocation of the cache entry, the response falls back to text-only and
 * never reports the image as included; pixel bytes never leak through
 * structuredContent or metadata; error status is propagated as isError.
 */
class McpImageResponseEncodingTest {

    private val json = Json

    @Before
    fun consentGrantedForImageTests() { ImageRevocationAuthority.grantConsent() }

    @After
    fun cleanCacheState() {
        VisualImageCache.clear()
        ImageRevocationAuthority.revokeConsent()
    }

    private val imageBytes = byteArrayOf(0x00, 0x01, 0x02, (0xFF).toByte(), 0x7F, 0x10)
    private val proof = VisualTargetProof(
        ScreenImage(200, 400, "image/jpeg", imageBytes, ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
        "hash",
        ai.closepaw.chatgpt.control.TapBounds(40, 100, 440, 900),
        "rotation0",
        System.currentTimeMillis(),
    )

    private fun operationHandler() = McpJsonRpcHandler(
        ClosePawStatusTool("0.1.17", 18),
        operationTools = McpOperationTools { name, _ ->
            kotlinx.serialization.json.buildJsonObject {
                put("status", "succeeded")
                put("operation", name)
                put("data", kotlinx.serialization.json.buildJsonObject {
                    put("visual", kotlinx.serialization.json.buildJsonObject { put("visual_id", proof.id) })
                })
            }
        },
    )

    private fun call(
        handler: McpJsonRpcHandler,
        name: String,
        id: Int = 1,
        arguments: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {},
    ) = handler.handle(
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""
    )

    // read_app requires an app argument before the response encoder is reached.
    private fun callReadApp(handler: McpJsonRpcHandler) = call(
        handler, "read_app",
        arguments = kotlinx.serialization.json.buildJsonObject { put("app", "samsung_internet") },
    )

    private fun result(body: String): kotlinx.serialization.json.JsonObject =
        json.parseToJsonElement(body).jsonObject["result"]!!.jsonObject

    private fun contentArray(body: String): List<kotlinx.serialization.json.JsonObject> =
        result(body)["content"]!!.jsonArray.map { it.jsonObject }

    // ── Operation tool path: cached proof encoded into an image part ──

    @Test
    fun `live cached proof is encoded as an MCP image part with faithful base64`() {
        VisualImageCache.put(proof) { true }
        val response = call(operationHandler(), "discover_operations")

        assertThat(response.statusCode).isEqualTo(200)
        val parts = contentArray(response.body!!)
        assertThat(parts.count { it["type"]!!.jsonPrimitive.content == "image" }).isEqualTo(1)
        assertThat(parts.first()["type"]!!.jsonPrimitive.content).isEqualTo("text")

        val imagePart = parts.last()
        assertThat(imagePart["mimeType"]!!.jsonPrimitive.content).isEqualTo("image/jpeg")
        val decoded = Base64.getDecoder().decode(imagePart["data"]!!.jsonPrimitive.content)
        assertThat(decoded).isEqualTo(imageBytes)

        val structured = result(response.body!!)["structuredContent"]!!.jsonObject
        assertThat(structured["data"]!!.jsonObject["image_included"]!!.jsonPrimitive.content)
            .isEqualTo("true")
        assertThat(result(response.body!!)["isError"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    fun `revoked cache falls back to text-only and reports image not included`() {
        VisualImageCache.put(proof) { true }
        // Simulate the consent/session revocation path: drop every cached buffer.
        VisualImageCache.clear()

        val response = call(operationHandler(), "discover_operations")
        val parts = contentArray(response.body!!)

        assertThat(parts.map { it["type"]!!.jsonPrimitive.content }).containsExactly("text")
        val structured = result(response.body!!)["structuredContent"]!!.jsonObject
        assertThat(structured["data"]!!.jsonObject["image_included"]!!.jsonPrimitive.content)
            .isEqualTo("false")
        assertThat(result(response.body!!)["isError"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    fun `revoked gate cannot deliver a cached proof even with the id present in metadata`() {
        var live = true
        VisualImageCache.put(proof) { live }
        live = false
        val response = call(operationHandler(), "discover_operations")
        assertThat(contentArray(response.body!!).map { it["type"]!!.jsonPrimitive.content })
            .containsExactly("text")
    }

    @Test
    fun `structuredContent never carries pixel bytes even when an image is delivered`() {
        VisualImageCache.put(proof) { true }
        val response = call(operationHandler(), "discover_operations")

        val encoded = Base64.getEncoder().encodeToString(imageBytes)
        val structured = result(response.body!!)["structuredContent"]!!.jsonObject
        assertThat(structured.toString()).doesNotContain(encoded)
        assertThat(structured.toString()).doesNotContain("bytes")
        // And the encoded payload appears exactly once — inside the content part.
        assertThat(response.body!!.split(encoded).size - 1).isEqualTo(1)
    }

    @Test
    fun `no cached id in metadata means an image_included false will not fabricate one`() {
        val handler = McpJsonRpcHandler(
            ClosePawStatusTool("0.1.17", 18),
            operationTools = McpOperationTools { _, _ ->
                kotlinx.serialization.json.buildJsonObject {
                    put("status", "succeeded")
                    put("data", kotlinx.serialization.json.buildJsonObject {
                        put("visual", kotlinx.serialization.json.buildJsonObject {
                            put("visual_id", "never-cached-id")
                        })
                    })
                }
            },
        )
        VisualImageCache.put(proof) { true }
        val response = call(handler, "discover_operations")

        assertThat(contentArray(response.body!!).map { it["type"]!!.jsonPrimitive.content })
            .containsExactly("text")
        val structured = result(response.body!!)["structuredContent"]!!.jsonObject
        assertThat(structured["data"]!!.jsonObject["image_included"]!!.jsonPrimitive.content)
            .isEqualTo("false")
    }

    // ── read_app tool path: pre-encoded image fields ─────────────────

    private fun readAppHandler(status: String = "succeeded", base64: String?, mime: String?) =
        McpJsonRpcHandler(
            ClosePawStatusTool("0.1.17", 18),
            readAppTool = ReadAppTool { app ->
                ReadAppToolResult(
                    structured = kotlinx.serialization.json.buildJsonObject {
                        put("status", status)
                        put("summary", "read $app")
                        put("content", "screen read ok")
                    },
                    imageBase64 = base64,
                    imageMimeType = mime,
                    imageEpoch = ImageRevocationAuthority.currentEpoch,
                )
            },
        )

    @Test
    fun `read_app image fields are passed through verbatim into an image part`() {
        val encoded = Base64.getEncoder().encodeToString(imageBytes)
        val handler = readAppHandler(base64 = encoded, mime = "image/png")
        val response = callReadApp(handler)

        assertThat(response.statusCode).isEqualTo(200)
        val parts = contentArray(response.body!!)
        val imagePart = parts.single { it["type"]!!.jsonPrimitive.content == "image" }
        assertThat(imagePart["data"]!!.jsonPrimitive.content).isEqualTo(encoded)
        assertThat(imagePart["mimeType"]!!.jsonPrimitive.content).isEqualTo("image/png")
        val textPart = parts.single { it["type"]!!.jsonPrimitive.content == "text" }
        assertThat(textPart["text"]!!.jsonPrimitive.content).isEqualTo("screen read ok")
        assertThat(result(response.body!!)["isError"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    fun `read_app failure result is flagged isError and ships no image`() {
        val handler = readAppHandler(status = "failed", base64 = null, mime = null)
        val response = callReadApp(handler)
        assertThat(result(response.body!!)["isError"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(contentArray(response.body!!).map { it["type"]!!.jsonPrimitive.content })
            .containsExactly("text")
    }

    @Test
    fun `read_app with blank or half-present image fields ships text-only`() {
        val encoded = "aGVsbG8="
        for ((b64, mime) in listOf(null to "image/png", encoded to "", "" to "image/png", null to null)) {
            val handler = readAppHandler(base64 = b64, mime = mime)
            val response = callReadApp(handler)
            assertThat(contentArray(response.body!!).map { it["type"]!!.jsonPrimitive.content })
                .containsExactly("text")
        }
    }

    // ── Transport envelope sanity ────────────────────────────────────

    @Test
    fun `image-bearing responses remain a JSON-RPC result envelope`() {
        VisualImageCache.put(proof) { true }
        val response = call(operationHandler(), "discover_operations")
        val top = json.parseToJsonElement(response.body!!).jsonObject
        assertThat(top.keys).containsExactly("jsonrpc", "id", "result")
        assertThat(top["jsonrpc"]!!.jsonPrimitive.content).isEqualTo("2.0")
        assertThat(response.contentType).isEqualTo("application/json")
    }
    @Test
    fun stopSharingRevokesCachedVisualEvenWhenEntryWasNotExplicitlyCleared() {
        VisualImageCache.put(proof) { true }
        ImageRevocationAuthority.revokeConsent()
        val response = call(operationHandler(), "discover_operations")
        assertThat(contentArray(response.body!!).map { it["type"]!!.jsonPrimitive.content })
            .containsExactly("text")
    }

}
