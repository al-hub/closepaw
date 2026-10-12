package ai.closepaw.chatgpt

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class McpJsonRpcHandlerTest {
    private val json = Json

    @Test
    fun initializeAdvertisesOnlyToolsCapability() {
        val handler = McpJsonRpcHandler(ClosePawStatusTool("0.1.17", 18))
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""
        )

        assertThat(response.statusCode).isEqualTo(200)
        val result = json.parseToJsonElement(response.body!!).jsonObject["result"]!!.jsonObject
        assertThat(result["protocolVersion"]!!.jsonPrimitive.content).isEqualTo("2025-06-18")
        assertThat(result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("ClosePaw")
        assertThat(result["capabilities"]!!.jsonObject.keys).containsExactly("tools")
    }

    @Test
    fun listToolsWithoutReadExecutorExposesOnlyGetStatus() {
        val handler = McpJsonRpcHandler(ClosePawStatusTool("0.1.17", 18))
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
        )

        val tools = json.parseToJsonElement(response.body!!).jsonObject["result"]!!
            .jsonObject["tools"]!!.jsonArray
        assertThat(tools).hasSize(1)
        assertThat(tools.single().jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("get_status")
    }

    @Test
    fun listToolsWithReadExecutorExposesStableAppIndependentContract() {
        val handler = handlerWithReadApp()
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
        )

        val tools = json.parseToJsonElement(response.body!!).jsonObject["result"]!!
            .jsonObject["tools"]!!.jsonArray
        assertThat(tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
            .containsExactly("get_status", "read_app", "get_diagnostics")
        val readApp = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "read_app" }.jsonObject
        val description = readApp["description"]!!.jsonPrimitive.content
        assertThat(description).contains("single visible-screen snapshot")
        assertThat(description).contains("outside web search")
        assertThat(description).contains("get_status")
        assertThat(description).doesNotContain("Samsung")
        assertThat(description).doesNotContain("Chrome")
        assertThat(description).doesNotContain("Gemini")
        val schema = readApp["inputSchema"]!!.jsonObject
        val appSchema = schema["properties"]!!.jsonObject["app"]!!.jsonObject
        assertThat(appSchema["type"]!!.jsonPrimitive.content).isEqualTo("string")
        assertThat(appSchema.keys).containsExactly("type", "description")
        assertThat(schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("app")
        assertThat(schema["additionalProperties"]!!.jsonPrimitive.content).isEqualTo("false")
        val annotations = readApp["annotations"]!!.jsonObject
        assertThat(annotations["readOnlyHint"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(annotations["destructiveHint"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    fun getStatusReturnsActualClosePawIdentity() {
        val handler = McpJsonRpcHandler(ClosePawStatusTool("0.1.17", 18))
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_status","arguments":{}}}"""
        )

        val structured = json.parseToJsonElement(response.body!!).jsonObject["result"]!!
            .jsonObject["structuredContent"]!!.jsonObject
        assertThat(structured["status"]!!.jsonPrimitive.content).isEqualTo("succeeded")
        assertThat(structured["app"]!!.jsonPrimitive.content).isEqualTo("ClosePaw")
        assertThat(structured["version_name"]!!.jsonPrimitive.content).isEqualTo("0.1.17")
    }

    @Test
    fun readAppReturnsVisibleContentFromExecutor() {
        val handler = handlerWithReadApp()
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"read_app","arguments":{"app":"samsung_internet"}}}"""
        )

        val result = json.parseToJsonElement(response.body!!).jsonObject["result"]!!.jsonObject
        val structured = result["structuredContent"]!!.jsonObject
        assertThat(structured["app"]!!.jsonPrimitive.content).isEqualTo("samsung_internet")
        assertThat(structured["content"]!!.jsonPrimitive.content).isEqualTo("Visible browser text")
        assertThat(result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
            .isEqualTo("Visible browser text")
        assertThat(result["isError"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    fun readAppAcceptsGeminiToolRequest() {
        val handler = handlerWithReadApp()
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"read_app","arguments":{"app":"gemini"}}}"""
        )
        val result = json.parseToJsonElement(response.body!!).jsonObject["result"]!!.jsonObject
        val structured = result["structuredContent"]!!.jsonObject
        assertThat(structured["app"]!!.jsonPrimitive.content).isEqualTo("gemini")
        assertThat(result["isError"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    fun readAppDelegatesFutureAppIdentifiersWithoutChangingMcpContract() {
        val response = handlerWithReadApp().handle(
            """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"read_app","arguments":{"app":"future_app"}}}"""
        )
        val result = json.parseToJsonElement(response.body!!).jsonObject["result"]!!.jsonObject
        assertThat(result["structuredContent"]!!.jsonObject["app"]!!.jsonPrimitive.content)
            .isEqualTo("future_app")
    }

    @Test
    fun readAppPreservesUnsupportedResultFromRuntime() {
        val handler = McpJsonRpcHandler(
            ClosePawStatusTool("0.1.17", 18),
            ReadAppTool { app -> ReadAppToolResult(buildJsonObject {
                put("status", "failed")
                put("error_code", "unsupported_app")
                put("app", app)
                put("summary", "App is not supported.")
            }) },
        )
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"read_app","arguments":{"app":"unsupported_app"}}}"""
        )
        val result = json.parseToJsonElement(response.body!!).jsonObject["result"]!!.jsonObject
        assertThat(result["isError"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(result["structuredContent"]!!.jsonObject["error_code"]!!.jsonPrimitive.content)
            .isEqualTo("unsupported_app")
    }

    @Test
    fun readAppRequiresAppArgument() {
        val handler = handlerWithReadApp()
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"read_app","arguments":{}}}"""
        )

        val error = json.parseToJsonElement(response.body!!).jsonObject["error"]!!.jsonObject
        assertThat(error["code"]!!.jsonPrimitive.content).isEqualTo("-32602")
    }

    @Test
    fun unknownToolIsRejectedInsteadOfPretendingExecution() {
        val handler = handlerWithReadApp()
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"run_task","arguments":{}}}"""
        )

        val error = json.parseToJsonElement(response.body!!).jsonObject["error"]!!.jsonObject
        assertThat(error["code"]!!.jsonPrimitive.content).isEqualTo("-32602")
    }

    @Test
    fun malformedJsonReturnsParseError() {
        val handler = handlerWithReadApp()
        val response = handler.handle("{")

        val error = json.parseToJsonElement(response.body!!).jsonObject["error"]!!.jsonObject
        assertThat(error["code"]!!.jsonPrimitive.content).isEqualTo("-32700")
    }

    private fun handlerWithReadApp(): McpJsonRpcHandler =
        McpJsonRpcHandler(
            statusTool = ClosePawStatusTool("0.1.17", 18),
            readAppTool = ReadAppTool { app ->
                ReadAppToolResult(
                    structured = buildJsonObject {
                        put("status", "succeeded")
                        put("summary", "Read visible browser content.")
                        put("app", app)
                        put("content", "Visible browser text")
                    },
                )
            },
        )
}
