package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.operations.*
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test

class GeminiDraftExpansionTest {
    @Test fun actualDraftOperationUsesUnchangedEightToolDefinitionsAndDeduplicates() {
        val registry = OperationRegistry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val file = File.createTempFile("draft-ledger", ".json").apply { delete() }
        try {
            var writes = 0
            val dispatcher = OperationDispatcher(registry, OperationTaskStore(file), scope)
            val handler = McpJsonRpcHandler(ClosePawStatusTool("test", 1),
                readAppTool = ReadAppTool { error("unused") }, operationTools = McpOperationTools { name, args -> when(name) {
                    "discover_operations" -> dispatcher.discover(args)
                    "execute" -> dispatcher.execute(args)
                    else -> error("unused")
                } })
            fun tools() = Json.parseToJsonElement(handler.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").body!!)
            val before = tools()
            assertThat(before.jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray).hasSize(8)
            val descriptor = GeminiDraftOperation.descriptor()
            registry.register(descriptor, object : OperationHandler {
                override suspend fun invoke(invocation: OperationInvocation): OperationResult { writes++; return OperationResult() }
            })
            assertThat(tools()).isEqualTo(before)
            assertThat(dispatcher.discover().toString()).contains("gemini.draft.insert")
            val args = buildJsonObject {
                put("operation_id", descriptor.id); put("operation_version", descriptor.version)
                put("schema_hash", descriptor.schemaHash); put("request_id", "draft-test")
                put("input", buildJsonObject { put("text", "초안 😀") })
            }
            val task = dispatcher.execute(args)
            assertThat(task["task_id"]).isNotNull()
            assertThat(dispatcher.execute(args)["task_id"]).isEqualTo(task["task_id"])
            assertThat(writes).isEqualTo(1)
            assertThat(file.readText()).doesNotContain("초안")
        } finally { scope.cancel(); file.delete() }
    }
    @Test fun operationPermissionDoesNotEnableOtherOperations() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val file = File.createTempFile("draft-permission", ".json").apply { delete() }
        try {
            val registry = OperationRegistry(); val descriptor = GeminiDraftOperation.descriptor()
            registry.register(descriptor, object : OperationHandler {
                override suspend fun invoke(invocation: OperationInvocation): OperationResult = error("must not run")
            })
            val dispatcher = OperationDispatcher(registry, OperationTaskStore(file), scope, operationAllowed = { false })
            val result = dispatcher.execute(buildJsonObject { put("operation_id", descriptor.id) })
            assertThat(result["error_code"]!!.jsonPrimitive.content).isEqualTo("permission_denied")
        } finally { scope.cancel(); file.delete() }
    }
    @Test fun diagnosticsSurviveRecreationAndStayBounded() {
        val file = File.createTempFile("draft-diagnostics", ".json").apply { delete() }
        try {
            val logger = GeminiDraftDiagnostics(file)
            repeat(80) { logger.record(DraftDiagnostic("task-$it", "verify", "ok", it.toLong())) }
            val events = GeminiDraftDiagnostics(file).snapshot()["events"]!!.jsonArray
            assertThat(events).hasSize(64)
            assertThat(events.first().jsonObject["task_id"]!!.jsonPrimitive.content).isEqualTo("task-16")
        } finally { file.delete() }
    }
    @Test fun composerPolicyRejectsSearchAndGenericEditors() {
        assertThat(GeminiDraftComposerPolicy.isComposer("검색", "", "search_input")).isFalse()
        assertThat(GeminiDraftComposerPolicy.isComposer("", "", "input_text")).isFalse()
        assertThat(GeminiDraftComposerPolicy.isComposer("Gemini에게 물어보세요", "", "")).isTrue()
        assertThat(GeminiDraftComposerPolicy.isComposer("Ask Gemini", "", "")).isTrue()
    }
}
