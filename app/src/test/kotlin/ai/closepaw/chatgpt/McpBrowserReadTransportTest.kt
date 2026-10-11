package ai.closepaw.chatgpt

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

/** Regression coverage for the image-bearing MCP contract used by ChatGPT Text/Voice. */
class McpBrowserReadTransportTest {
    private val json = Json

    @Test
    fun screenshotOcrTextIsModelReadableWithoutInterpretingImageBlock() {
        val recognized = "(대전) 천주교 대전교구 주교좌 대흥동 성당"
        val response = call(ReadAppTool { app ->
            ReadAppToolResult(
                structured = buildJsonObject {
                    put("status", "succeeded")
                    put("summary", "Read visible page text from on-device screenshot OCR.")
                    put("app", app)
                    put("content", recognized)
                    put("text_source", "on_device_ocr")
                    put("capture_source", "screenshot_ocr")
                },
                imageBase64 = "AQID",
                imageMimeType = "image/jpeg",
            )
        })
        val items = response["content"]!!.jsonArray
        assertThat(items).hasSize(2)
        assertThat(items[0].jsonObject["text"]!!.jsonPrimitive.content).isEqualTo(recognized)
        assertThat(items[1].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("image")
        assertThat(response["structuredContent"]!!.jsonObject["text_source"]!!
            .jsonPrimitive.content).isEqualTo("on_device_ocr")
    }

    @Test
    fun screenshotOnlyReadIncludesMcpImageContentForVision() {
        val result = call(ReadAppTool { app ->
            ReadAppToolResult(
                structured = buildJsonObject {
                    put("status", "succeeded")
                    put("summary", "Captured visible screen for visual reading.")
                    put("app", app)
                    put("capture_source", "screenshot")
                    put("screenshot_attached", true)
                    put("content", "")
                },
                imageBase64 = "AQID",
                imageMimeType = "image/jpeg",
            )
        })

        val content = result["content"]!!.jsonArray
        assertThat(content).hasSize(2)
        assertThat(content[0].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("text")
        assertThat(content[0].jsonObject["text"]!!.jsonPrimitive.content)
            .isEqualTo("Captured visible screen for visual reading.")
        assertThat(content[1].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("image")
        assertThat(content[1].jsonObject["data"]!!.jsonPrimitive.content).isEqualTo("AQID")
        assertThat(content[1].jsonObject["mimeType"]!!.jsonPrimitive.content)
            .isEqualTo("image/jpeg")
        assertThat(result["structuredContent"]!!.jsonObject["screenshot_attached"]!!
            .jsonPrimitive.content).isEqualTo("true")
        assertThat(result["isError"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    fun accessibilityFailureIsReportedAsToolErrorWithoutImage() {
        val result = call(ReadAppTool {
            ReadAppToolResult(
                structured = buildJsonObject {
                    put("status", "failed")
                    put("reason_code", "accessibility_not_ready")
                    put("summary", "Accessibility permission is missing.")
                },
            )
        })

        assertThat(result["isError"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(result["content"]!!.jsonArray).hasSize(1)
        assertThat(result["content"]!!.jsonArray[0].jsonObject["text"]!!
            .jsonPrimitive.content).isEqualTo("Accessibility permission is missing.")
        assertThat(result["structuredContent"]!!.jsonObject["reason_code"]!!
            .jsonPrimitive.content).isEqualTo("accessibility_not_ready")
    }

    @Test
    fun noImageMimeTypeMeansNoUnusableImageBlock() {
        val result = call(ReadAppTool {
            ReadAppToolResult(
                structured = buildJsonObject {
                    put("status", "succeeded")
                    put("summary", "Read visible page text.")
                    put("content", "Readable browser text")
                },
                imageBase64 = "AQID",
                imageMimeType = null,
            )
        })

        assertThat(result["content"]!!.jsonArray).hasSize(1)
        assertThat(result["content"]!!.jsonArray[0].jsonObject["text"]!!
            .jsonPrimitive.content).isEqualTo("Readable browser text")
    }

    private fun call(readApp: ReadAppTool): JsonObject {
        val handler = McpJsonRpcHandler(
            statusTool = ClosePawStatusTool("0.1.34", 35),
            readAppTool = readApp,
        )
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"read_app","arguments":{"app":"samsung_internet"}}}"""
        )
        assertThat(response.statusCode).isEqualTo(200)
        return json.parseToJsonElement(response.body!!).jsonObject["result"]!!.jsonObject
    }
}
