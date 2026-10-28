package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.operations.*
import ai.closepaw.security.ImageRevocationAuthority
import org.junit.After
import kotlinx.coroutines.*
import java.io.File
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Test

class McpOperationContractTest {
    @After fun cleanupImageConsentFixture() {
        ai.closepaw.chatgpt.control.VisualImageCache.clear()
        ImageRevocationAuthority.revokeConsent()
    }
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
        val args = buildJsonObject { put("operation_id", "future.read"); put("operation_version", 1); put("schema_hash", "hash"); put("input_json", "{\"new_field\":42}") }
        fun call(arguments: JsonObject): JsonObject = Json.parseToJsonElement(handler.handle(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call"); put("params", buildJsonObject { put("name", "query"); put("arguments", arguments) })
        }.toString()).body!!).jsonObject
        assertThat(call(args)["result"]).isNotNull()
        assertThat(received!!["input"]!!.jsonObject["new_field"]!!.jsonPrimitive.int).isEqualTo(42)
        received = null
        assertThat(call(JsonObject(args + ("approval" to JsonPrimitive(true))))["error"]).isNotNull()
        assertThat(received).isNull()
    }

    @Test fun futureOperationIsDiscoverableAndCallableWithoutChangingToolSchemas() {
        val registry = OperationRegistry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ledger = File.createTempFile("mcp-task", ".json").apply { delete() }
        try {
            val dispatcher = OperationDispatcher(registry, OperationTaskStore(ledger), scope)
            val handler = handler { name, args -> when(name) {
                "discover_operations" -> dispatcher.discover(args)
                "query" -> dispatcher.query(args)
                else -> error("unexpected tool")
            } }
            fun rpc(method: String, params: JsonObject=buildJsonObject {}): JsonObject =
                Json.parseToJsonElement(handler.handle(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params)
                }.toString()).body!!).jsonObject.getValue("result").jsonObject
            val original = rpc("tools/list")
            val descriptor = OperationDescriptor("future.numeric.read", "Future structured query", inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"value":{"type":"integer"}},"required":["value"],"additionalProperties":false}""").jsonObject)
            registry.register(descriptor, object: OperationHandler {
                override suspend fun invoke(invocation: OperationInvocation) = OperationResult(data=invocation.input)
            })
            assertThat(rpc("tools/list")).isEqualTo(original)
            val detail = rpc("tools/call", buildJsonObject { put("name", "discover_operations"); put("arguments", buildJsonObject { put("operation_id", descriptor.id) }) })
            assertThat(detail["structuredContent"]!!.jsonObject["operations"]!!.jsonArray[0].jsonObject["input_schema"]).isEqualTo(descriptor.inputSchema)
            val called = rpc("tools/call", buildJsonObject { put("name", "query"); put("arguments", buildJsonObject {
                put("operation_id", descriptor.id); put("operation_version", descriptor.version); put("schema_hash", descriptor.schemaHash); put("input_json", "{\"value\":42}")
            }) })
            assertThat(called["structuredContent"]!!.jsonObject["data"]!!.jsonObject["value"]!!.jsonPrimitive.int).isEqualTo(42)
        } finally { scope.cancel(); ledger.delete() }
    }

    @Test fun rootVisualFallbackMetadataMatchesActualImageDespiteEmptyNestedData() {
        // Test-only consent fixture: production must obtain real OS projection consent.
        ImageRevocationAuthority.grantConsent()
        val proof = ai.closepaw.chatgpt.control.VisualTargetProof(
            ai.closepaw.model.ScreenImage(1,1,"image/jpeg",byteArrayOf(1), ai.closepaw.model.ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
            "pixels", ai.closepaw.chatgpt.control.TapBounds(0,0,1,1), "window", System.currentTimeMillis())
        for (nested in listOf(false, true)) {
            var allowed = true
            ai.closepaw.chatgpt.control.VisualImageCache.put(proof) { allowed }
            val handler = handler { _, _ -> buildJsonObject {
                put("visual", proof.metadata()); put("image_included", false)
                if (nested) put("result", buildJsonObject { put("data", buildJsonObject {}) })
                else put("data", buildJsonObject {})
            } }
            val request = """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"query","arguments":{"operation_id":"android.screen.observe","operation_version":1,"schema_hash":"hash","input_json":"{}"}}}"""
            fun response() = Json.parseToJsonElement(handler.handle(request).body!!).jsonObject.getValue("result").jsonObject
            val delivered = response()
            assertThat(delivered.getValue("content").jsonArray).hasSize(2)
            assertThat(delivered.getValue("structuredContent").jsonObject.getValue("image_included").jsonPrimitive.boolean).isTrue()
            allowed = false
            val revoked = response()
            assertThat(revoked.getValue("content").jsonArray).hasSize(1)
            assertThat(revoked.getValue("structuredContent").jsonObject.getValue("image_included").jsonPrimitive.boolean).isFalse()
        }
    }

    @Test fun screenObserveQueryReturnsActualImageBlockAndRevocationRemovesIt() {
        // Test-only consent fixture: production must obtain real OS projection consent.
        ImageRevocationAuthority.grantConsent()
        val image = ai.closepaw.model.ScreenImage(2, 3, "image/jpeg", byteArrayOf(1,2,3),
            ai.closepaw.model.ScreenImageSource.ACCESSIBILITY_SCREENSHOT)
        val proof = ai.closepaw.chatgpt.control.VisualTargetProof(image, "pixels",
            ai.closepaw.chatgpt.control.TapBounds(0,0,2,3), "window", System.currentTimeMillis())
        var allowed = true
        ai.closepaw.chatgpt.control.VisualImageCache.put(proof) { allowed }
        val handler = handler { name, _ ->
            assertThat(name).isEqualTo("query")
            buildJsonObject { put("status", "succeeded"); put("data", buildJsonObject { put("visual", proof.metadata()); put("image_included", false) }) }
        }
        val request = """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"query","arguments":{"operation_id":"android.screen.observe","operation_version":1,"schema_hash":"hash","input_json":"{}"}}}"""
        fun response() = Json.parseToJsonElement(handler.handle(request).body!!).jsonObject.getValue("result").jsonObject
        val delivered = response()
        val blocks = delivered.getValue("content").jsonArray
        assertThat(delivered.getValue("structuredContent").jsonObject.getValue("data").jsonObject.getValue("image_included").jsonPrimitive.boolean).isTrue()
        assertThat(blocks[0].jsonObject.getValue("text").jsonPrimitive.content).contains("\"image_included\":true")
        assertThat(blocks.map { it.jsonObject.getValue("type").jsonPrimitive.content }).containsExactly("text", "image").inOrder()
        assertThat(blocks[1].jsonObject.getValue("data").jsonPrimitive.content).isEqualTo("AQID")
        assertThat(blocks[1].jsonObject.getValue("mimeType").jsonPrimitive.content).isEqualTo("image/jpeg")
        allowed = false
        val revoked = response()
        assertThat(revoked.getValue("content").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content }).containsExactly("text")
        assertThat(revoked.getValue("structuredContent").jsonObject.getValue("data").jsonObject.getValue("image_included").jsonPrimitive.boolean).isFalse()
    }

    @Test fun getTaskImageMetadataMatchesActualBlockAfterRevocation() {
        // Test-only consent fixture: production must obtain real OS projection consent.
        ImageRevocationAuthority.grantConsent()
        val image = ai.closepaw.model.ScreenImage(2, 3, "image/jpeg", byteArrayOf(1,2,3),
            ai.closepaw.model.ScreenImageSource.ACCESSIBILITY_SCREENSHOT)
        val proof = ai.closepaw.chatgpt.control.VisualTargetProof(image, "pixels",
            ai.closepaw.chatgpt.control.TapBounds(0,0,2,3), "window", System.currentTimeMillis())
        var allowed = true
        ai.closepaw.chatgpt.control.VisualImageCache.put(proof) { allowed }
        val handler = handler { name, _ ->
            assertThat(name).isEqualTo("get_task")
            buildJsonObject { put("status", "succeeded"); put("result", buildJsonObject {
                put("data", buildJsonObject { put("visual", proof.metadata()); put("image_included", false) })
            }) }
        }
        val request = """{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"get_task","arguments":{"task_id":"test-task"}}}"""
        fun response() = Json.parseToJsonElement(handler.handle(request).body!!).jsonObject.getValue("result").jsonObject
        fun included(result: JsonObject) = result.getValue("structuredContent").jsonObject.getValue("result").jsonObject
            .getValue("data").jsonObject.getValue("image_included").jsonPrimitive.boolean
        val delivered = response()
        assertThat(included(delivered)).isTrue()
        assertThat(delivered.getValue("content").jsonArray).hasSize(2)
        allowed = false
        val revoked = response()
        assertThat(included(revoked)).isFalse()
        assertThat(revoked.getValue("content").jsonArray).hasSize(1)
    }

    @Test fun malformedArgumentsAndUnsupportedProtocolDoNotCrashOrReflectUnknownVersion() {
        val handler = handler()
        assertThat(handler.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":[]}""").body).contains("error")
        val initialized = handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2099-01-01"}}""")
        assertThat(initialized.body).contains("2025-06-18")
        assertThat(initialized.body).doesNotContain("2099-01-01")
    }

    @Test fun tailscaleExpansionUsesTheSameMcpDefinitionsAndWorksWithoutTermux() {
        val registry = OperationRegistry()
        val file = File.createTempFile("tailscale-contract", ".json").apply { delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val dispatcher = OperationDispatcher(registry, OperationTaskStore(file), scope)
            val transport = handler { name, args -> when (name) {
                "discover_operations" -> dispatcher.discover(args)
                "query" -> dispatcher.query(args)
                else -> error("unexpected operation")
            } }
            val before = transport.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").body
            val platform = object : ai.closepaw.tailscale.TailscalePlatform {
                override suspend fun observe() = ai.closepaw.tailscale.TailscaleLocalState(installed = false)
                override suspend fun probe(target: ai.closepaw.tailscale.PrivateTarget, timeoutMs: Int) = error("no target requested")
                override suspend fun connect() = error("read must never connect")
            }
            ai.closepaw.tailscale.TailscaleOperations(ai.closepaw.tailscale.TailscaleRecovery(platform, { false }), { false }).register(registry)
            assertThat(transport.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").body).isEqualTo(before)
            val descriptor = registry.get(ai.closepaw.tailscale.TailscaleOperations.STATUS)!!.descriptor
            val response = transport.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"query","arguments":{"operation_id":"${descriptor.id}","operation_version":1,"schema_hash":"${descriptor.schemaHash}","input_json":"{}"}}}""")
            val data = Json.parseToJsonElement(response.body!!).jsonObject.getValue("result").jsonObject.getValue("structuredContent").jsonObject.getValue("data").jsonObject
            assertThat(data.getValue("installed").jsonPrimitive.boolean).isFalse()
            assertThat(data.getValue("state").jsonPrimitive.content).isEqualTo("app_not_installed")
        } finally { scope.cancel(); file.delete() }
    }
}
