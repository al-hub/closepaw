package ai.closepaw.chatgpt

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class McpJsonRpcHandlerTest {
    private val json = Json
    private val handler = McpJsonRpcHandler(ClosePawStatusTool("0.1.16", 16))

    @Test
    fun initializeAdvertisesOnlyToolsCapability() {
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
    fun listToolsExposesOnlyReadOnlyGetStatus() {
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
        )

        val tools = json.parseToJsonElement(response.body!!).jsonObject["result"]!!
            .jsonObject["tools"]!!.jsonArray
        assertThat(tools).hasSize(1)
        val tool = tools.single().jsonObject
        assertThat(tool["name"]!!.jsonPrimitive.content).isEqualTo("get_status")
        val annotations = tool["annotations"]!!.jsonObject
        assertThat(annotations["readOnlyHint"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(annotations["destructiveHint"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    fun getStatusReturnsActualClosePawIdentity() {
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_status","arguments":{}}}"""
        )

        val structured = json.parseToJsonElement(response.body!!).jsonObject["result"]!!
            .jsonObject["structuredContent"]!!.jsonObject
        assertThat(structured["status"]!!.jsonPrimitive.content).isEqualTo("succeeded")
        assertThat(structured["app"]!!.jsonPrimitive.content).isEqualTo("ClosePaw")
        assertThat(structured["version_name"]!!.jsonPrimitive.content).isEqualTo("0.1.16")
    }

    @Test
    fun unknownToolIsRejectedInsteadOfPretendingExecution() {
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"run_task","arguments":{}}}"""
        )

        val error = json.parseToJsonElement(response.body!!).jsonObject["error"]!!.jsonObject
        assertThat(error["code"]!!.jsonPrimitive.content).isEqualTo("-32602")
    }

    @Test
    fun malformedJsonReturnsParseError() {
        val response = handler.handle("{")

        val error = json.parseToJsonElement(response.body!!).jsonObject["error"]!!.jsonObject
        assertThat(error["code"]!!.jsonPrimitive.content).isEqualTo("-32700")
    }
}
