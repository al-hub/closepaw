package ai.closepaw.chatgpt

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Test

class McpOperationContractTest {
    private fun handler(call: (String, JsonObject) -> JsonObject = { _, _ -> buildJsonObject { put("status", "succeeded") } }) =
        McpJsonRpcHandler(ClosePawStatusTool("test", 1), operationTools = McpOperationTools(call))

    @Test fun fixedToolsHaveNoAppOrOperationEnumAndExecuteIsNotReadOnly() {
        val response = handler().handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        val tools = Json.parseToJsonElement(response.body!!).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertThat(tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }).containsAtLeast(
            "discover_operations", "query", "execute", "get_task", "cancel_task")
        val execute = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "execute" }.jsonObject
        assertThat(execute["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(execute["inputSchema"]!!.jsonObject["properties"]!!.jsonObject["input_json"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("string")
        assertThat(execute.toString()).doesNotContain("enum")
    }

    @Test fun jsonPayloadIsParsedBeforeDispatchAndExtraTopLevelArgumentsRejected() {
        var received: JsonObject? = null
        val handler = handler { _, args -> received = args; buildJsonObject { put("status", "queued") } }
        val args = buildJsonObject { put("operation_id", "future.read"); put("operation_version", "1"); put("schema_hash", "hash"); put("input_json", "{\"new_field\":42}") }
        fun call(arguments: JsonObject): JsonObject = Json.parseToJsonElement(handler.handle(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call"); put("params", buildJsonObject { put("name", "query"); put("arguments", arguments) })
        }.toString()).body!!).jsonObject
        assertThat(call(args)["result"]).isNotNull()
        assertThat(received!!["input"]!!.jsonObject["new_field"]!!.jsonPrimitive.int).isEqualTo(42)
        received = null
        assertThat(call(JsonObject(args + ("approval" to JsonPrimitive(true))))["error"]).isNotNull()
        assertThat(received).isNull()
    }

    @Test fun malformedArgumentsAndUnsupportedProtocolDoNotCrashOrReflectUnknownVersion() {
        val handler = handler()
        assertThat(handler.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":[]}""").body).contains("error")
        val initialized = handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2099-01-01"}}""")
        assertThat(initialized.body).contains("2025-06-18")
        assertThat(initialized.body).doesNotContain("2099-01-01")
    }
}
