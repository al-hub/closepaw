package ai.closepaw.chatgpt.operations

import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OperationDispatcherTest {
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"command":{"type":"string","minLength":1}},"required":["command"],"additionalProperties":false}""").jsonObject
    private fun args(id: String, request: String = "r1", command: String = "echo test") = buildJsonObject {
        put("operation_id", id); put("request_id", request); put("operation_version", 1); put("schema_hash", operationHash(canonicalJson(schema)))
        put("input", buildJsonObject { put("command", command) })
    }
    @Test fun queryCannotInvokeWrites() {
        val registry = OperationRegistry()
        registry.register(OperationDescriptor("shell", "Shell", inputSchema = schema, readOnly = false), object: OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation) = error("must not execute")
        })
        val dispatcher = OperationDispatcher(registry, OperationTaskStore(File.createTempFile("tasks", ".json").apply { delete() }), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        assertEquals("write_operation_requires_execute", dispatcher.query(args("shell"))["error_code"]?.jsonPrimitive?.content)
    }
    @Test fun invalidInputsDoNotReachHandler() {
        val registry = OperationRegistry()
        registry.register(OperationDescriptor("shell", "Shell", inputSchema = schema, readOnly = false), object: OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation) = error("must not execute")
        })
        val dispatcher = OperationDispatcher(registry, OperationTaskStore(File.createTempFile("tasks", ".json").apply { delete() }), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        assertEquals("invalid_input", dispatcher.execute(args("shell", command = ""))["error_code"]?.jsonPrimitive?.content)
    }
    @Test fun dedupSurvivesRestartAndRejectsConflict() = runBlocking {
        val registry = OperationRegistry()
        val gate = CompletableDeferred<Unit>()
        registry.register(OperationDescriptor("shell", "Shell", inputSchema = schema, readOnly = false), object: OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation): OperationResult { gate.await(); return OperationResult(stdout = "ok") }
        })
        val file = File.createTempFile("tasks", ".json").apply { delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = OperationDispatcher(registry, OperationTaskStore(file), scope)
        val first = dispatcher.execute(args("shell"))
        assertEquals(first["task_id"], dispatcher.execute(args("shell"))["task_id"])
        assertEquals("request_id_conflict", dispatcher.execute(args("shell", command = "other"))["error_code"]?.jsonPrimitive?.content)
        val restarted = OperationDispatcher(registry, OperationTaskStore(file), scope)
        assertEquals(first["task_id"], restarted.execute(args("shell"))["task_id"])
        gate.complete(Unit); scope.cancel()
    }
    @Test fun schemaGuardAndLocalPermissionFailBeforeExecution() {
        val registry = OperationRegistry()
        registry.register(OperationDescriptor("shell", "Shell", inputSchema = schema, readOnly = false), object: OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation) = error("must not execute")
        })
        val store = OperationTaskStore(File.createTempFile("tasks", ".json").apply { delete() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        assertEquals("permission_denied", OperationDispatcher(registry, store, scope) { false }.execute(args("shell"))["error_code"]?.jsonPrimitive?.content)
        val stale = JsonObject(args("shell") + ("schema_hash" to JsonPrimitive("old")))
        assertEquals("schema_changed", OperationDispatcher(registry, store, scope).execute(stale)["error_code"]?.jsonPrimitive?.content)
        scope.cancel()
    }
    @Test fun outputIsPagedAndPrivateFingerprintIsHidden() {
        val registry = OperationRegistry()
        val store = OperationTaskStore(File.createTempFile("tasks", ".json").apply { delete() })
        store.put(buildJsonObject {
            put("task_id", "task"); put("request_id", "r"); put("fingerprint", "private"); put("state", "succeeded")
            put("result", buildJsonObject { put("stdout", "abcdef"); put("stderr", "xyz") })
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val result = OperationDispatcher(registry, store, scope).getTask(buildJsonObject { put("task_id", "task"); put("offset", 2); put("limit", 2) })
        assertFalse(result.containsKey("fingerprint"))
        assertEquals("cd", result["result"]!!.jsonObject["stdout"]!!.jsonPrimitive.content)
        assertEquals(true, result["has_more"]!!.jsonPrimitive.boolean)
        scope.cancel()
    }
    @Test fun discoveryIsPagedAndSchemasRequireDetailLookup() {
        val registry = OperationRegistry()
        val handler = object: OperationHandler { override suspend fun invoke(invocation: OperationInvocation) = OperationResult() }
        registry.register(OperationDescriptor("alpha", "First", inputSchema = schema), handler)
        registry.register(OperationDescriptor("beta", "Second", inputSchema = schema), handler)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = OperationDispatcher(registry, OperationTaskStore(File.createTempFile("tasks", ".json").apply { delete() }), scope)
        val page = dispatcher.discover(buildJsonObject { put("limit", 1) })
        assertEquals(1, page["operations"]!!.jsonArray.size)
        assertFalse(page["operations"]!!.jsonArray[0].jsonObject.containsKey("input_schema"))
        assertTrue(page["has_more"]!!.jsonPrimitive.boolean)
        val detail = dispatcher.discover(buildJsonObject { put("operation_id", "alpha") })
        assertEquals(schema, detail["operations"]!!.jsonArray[0].jsonObject["input_schema"])
        assertEquals("unknown_operation", dispatcher.discover(buildJsonObject { put("operation_id", "absent") })["error_code"]!!.jsonPrimitive.content)
        scope.cancel()
    }
    @Test fun readinessExceptionsFailClosed() {
        val descriptor = OperationDescriptor("broken", "Broken", inputSchema = schema, readiness = { error("sensitive reason") })
        val registry = OperationRegistry()
        registry.register(descriptor, object: OperationHandler { override suspend fun invoke(invocation: OperationInvocation) = error("must not invoke") })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = OperationDispatcher(registry, OperationTaskStore(File.createTempFile("tasks", ".json").apply { delete() }), scope)
        assertFalse(descriptor.toJson()["ready"]!!.jsonPrimitive.boolean)
        assertEquals("not_ready", dispatcher.query(args("broken"))["error_code"]!!.jsonPrimitive.content)
        scope.cancel()
    }
    @Test fun asynchronousTerminalOutcomeIsPreserved() = runBlocking {
        val cases = listOf("outcome_unknown" to "outcome_unknown", "cancel_confirmed" to "cancelled", "timed_out" to "timed_out")
        for ((flag, expected) in cases) {
            val registry = OperationRegistry()
            registry.register(OperationDescriptor("shell", "Shell", inputSchema = schema, readOnly = false), object: OperationHandler {
                override suspend fun invoke(invocation: OperationInvocation) = OperationResult(success = false, data = buildJsonObject { put(flag, true) })
            })
            val store = OperationTaskStore(File.createTempFile("tasks", ".json").apply { delete() })
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val dispatcher = OperationDispatcher(registry, store, scope)
            val accepted = dispatcher.execute(args("shell"))
            val id = accepted["task_id"]!!.jsonPrimitive.content
            withTimeout(5000) {
                while(store.get(id)?.get("state")?.jsonPrimitive?.content in setOf("queued", "running")) delay(5)
            }
            assertEquals(expected, store.get(id)!!["state"]!!.jsonPrimitive.content)
            if(expected != "outcome_unknown") {
                assertFalse(store.updateActive(id, mapOf("state" to JsonPrimitive("outcome_unknown"))))
                assertEquals(expected, store.get(id)!!["state"]!!.jsonPrimitive.content)
            }
            scope.cancel()
        }
    }
    @Test fun registryRejectsDuplicateIds() {
        val registry = OperationRegistry()
        val handler = object: OperationHandler { override suspend fun invoke(invocation: OperationInvocation) = OperationResult() }
        registry.register(OperationDescriptor("one", "One", inputSchema = schema), handler)
        try { registry.register(OperationDescriptor("one", "One", inputSchema = schema), handler); fail("duplicate accepted") } catch (_: IllegalArgumentException) {}
    }
}
