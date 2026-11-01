package ai.closepaw.chatgpt.operations

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OperationTaskDiagnosticDispatchTest {
    @Test fun diagnosticQueriesNeverConstructFailedStoreAndAdmissionKeepsStorageIdentity() = runTest {
        val file = File.createTempFile("ledger", ".json")
        file.writeText("corrupt")
        var opened = 0
        var effects = 0
        val schema = buildJsonObject {
            put("type", "object"); put("properties", buildJsonObject {}); put("additionalProperties", false)
        }
        val registry = OperationRegistry()
        registry.register(OperationDescriptor("diagnostics", "Passive", inputSchema = schema), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation) = OperationResult(data = OperationTaskDiagnostics(file).snapshot())
        })
        registry.register(OperationDescriptor("write", "Write", inputSchema = schema, readOnly = false), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation): OperationResult {
                effects++; return OperationResult()
            }
        })
        val dispatcher = OperationDispatcher(registry, { opened++; OperationTaskStore(file) }, this)
        fun arguments(id: String) = buildJsonObject {
            put("operation_id", id); put("operation_version", 1)
            put("schema_hash", operationHash(canonicalJson(schema)))
            put("request_id", "one"); put("input", buildJsonObject {})
        }
        try {
            assertEquals("succeeded", dispatcher.query(arguments("diagnostics"))["status"]?.jsonPrimitive?.content)
            assertEquals(0, opened)
            val failure = dispatcher.execute(arguments("write"))
            assertEquals("task_storage_failed", failure["error_code"]?.jsonPrimitive?.content)
            assertEquals(false, failure["operation_started"]?.jsonPrimitive?.boolean)
            assertEquals(0, effects)
            assertEquals("corrupt", file.readText())
            assertEquals("succeeded", dispatcher.query(arguments("diagnostics"))["status"]?.jsonPrimitive?.content)
        } finally { file.delete(); File(file.path + ".lock").delete() }
    }
}
