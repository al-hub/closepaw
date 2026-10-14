package ai.closepaw.chatgpt

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Test

class McpRuntimeExpansionTest {
    private fun handler(expanded: Boolean) = McpJsonRpcHandler(
        ClosePawStatusTool("0.1.43", 44),
        readAppTool = ReadAppTool { error("Runtime metadata must never read or launch an app") },
        operationTools = McpOperationTools { _, _ -> error("Runtime metadata must not invoke operations") },
        runtimeInfoEnabled = expanded,
        extraDiagnostics = { buildJsonObject { put("gemini_draft", buildJsonObject { put("events", buildJsonArray {}) }) } },
    )
    private fun rpc(handler: McpJsonRpcHandler, request: String) = Json.parseToJsonElement(handler.handle(request).body!!).jsonObject
    private fun list(handler: McpJsonRpcHandler) = rpc(handler, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").getValue("result").jsonObject.getValue("tools").jsonArray
    private fun call(handler: McpJsonRpcHandler, args: String = "{}") = rpc(handler,
        """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_runtime_info","arguments":$args}}""")

    @Test fun ninthToolPreservesAllEightExistingDefinitionsExactly() {
        val baseline = list(handler(false)); val expanded = list(handler(true))
        assertThat(baseline).hasSize(8); assertThat(expanded).hasSize(9)
        assertThat(expanded.take(8)).containsExactlyElementsIn(baseline).inOrder()
        val added = expanded.last().jsonObject
        assertThat(added.getValue("name").jsonPrimitive.content).isEqualTo("get_runtime_info")
        assertThat(added.getValue("annotations").jsonObject.getValue("readOnlyHint").jsonPrimitive.boolean).isTrue()
    }
    @Test fun optionalInputAndAdditiveOutputWorkWithoutAnyAction() {
        val h = handler(true)
        val base = call(h).getValue("result").jsonObject.getValue("structuredContent").jsonObject
        val withLogs = call(h, """{"include_diagnostics":true}""").getValue("result").jsonObject.getValue("structuredContent").jsonObject
        assertThat(base.getValue("tool_count").jsonPrimitive.int).isEqualTo(9)
        assertThat(base.getValue("tool_schema_hash").jsonPrimitive.content).hasLength(64)
        assertThat(base["diagnostics"]).isNull()
        assertThat(withLogs["diagnostics"]).isNotNull()
        assertThat(JsonObject(withLogs.filterKeys { it != "diagnostics" })).isEqualTo(base)
        assertThat(base.getValue("client_refresh_verified").jsonPrimitive.boolean).isFalse()
    }
    @Test fun malformedNewToolInputIsRejected() {
        val h = handler(true)
        listOf("[]", "null", """{"include_diagnostics":"true"}""", """{"include_diagnostics":1}""", """{"extra":true}""").forEach {
            assertThat(call(h, it)["error"]).isNotNull()
        }
        assertThat(call(handler(false))["error"]).isNotNull()
    }
    @Test fun oldStatusToolAdvertisesServerExpansionWithoutClaimingClientRefresh() {
        val response = rpc(handler(true), """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_status","arguments":{}}}""")
        val status = response.getValue("result").jsonObject.getValue("structuredContent").jsonObject
        assertThat(status.getValue("mcp_tool_count").jsonPrimitive.int).isEqualTo(9)
        assertThat(status.getValue("runtime_info_tool").jsonPrimitive.content).isEqualTo("get_runtime_info")
        assertThat(status.getValue("tool_list_refresh_verified").jsonPrimitive.boolean).isFalse()
    }
    @Test fun googleSearchWithoutGeminiComposerIsNeverAWriteTarget() {
        assertThat(GeminiDraftTargetPolicy.recognized(AndroidGeminiDraftUi.GOOGLE_HOST, 0)).isFalse()
        assertThat(GeminiDraftTargetPolicy.recognized("com.android.chrome", 1)).isFalse()
        assertThat(GeminiDraftTargetPolicy.recognized(null, 1)).isFalse()
        assertThat(GeminiDraftTargetPolicy.recognized(AndroidGeminiDraftUi.GOOGLE_HOST, 1)).isTrue()
        assertThat(GeminiDraftTargetPolicy.recognized(AndroidGeminiDraftUi.PACKAGE, 0)).isTrue()
    }
    @Test fun googleHostRequiresGeminiSpecificIdentityNotGenericPromptLabels() {
        assertThat(GeminiDraftComposerPolicy.isComposer("Enter a prompt", "", "prompt_input", true)).isFalse()
        assertThat(GeminiDraftComposerPolicy.isComposer("무엇이든 물어보세요", "", "", true)).isFalse()
        assertThat(GeminiDraftComposerPolicy.isComposer("Ask Gemini", "", "", true)).isTrue()
        assertThat(GeminiDraftComposerPolicy.isComposer("", "", "assistant_robin_input_text", true)).isTrue()
    }
    @Test fun windowDiagnosticsContainOnlyMetadataAndPersistAcrossRecreation() {
        val file = java.io.File.createTempFile("gemini-window", ".json").apply { delete() }
        try {
            GeminiDraftDiagnostics(file).record(DraftDiagnostic("task", "launch", "foreground_timeout", 3,
                DraftWindowEvidence("com.android.systemui", AndroidGeminiDraftUi.GOOGLE_HOST, 12, 1, true, false, "google_host_unverified")))
            val event = GeminiDraftDiagnostics(file).snapshot().getValue("events").jsonArray.single().jsonObject
            val window = event.getValue("window").jsonObject
            assertThat(window.getValue("root_package").jsonPrimitive.content).isEqualTo(AndroidGeminiDraftUi.GOOGLE_HOST)
            assertThat(window.keys).containsExactly("observed_package", "root_package", "window_id", "window_type", "active", "focused", "window_reason")
            assertThat(event.keys).containsExactly("task_id", "stage", "reason_code", "elapsed_ms", "window", "timestamp_ms")
        } finally { file.delete() }
    }
}
