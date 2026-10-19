package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.operations.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GenericControlOperationsTest {
    private class Port : ControlPort {
        var writes = 0
        var appsFail = false
        override suspend fun capture() = ControlFrame("example.app", 1, "stable", listOf(ControlNode(1, "Menu", clickable = true)))
        override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
            writes++; return ControlEffect(true, true, "acknowledged")
        }
        override suspend fun apps(filter: String): JsonArray {
            if (appsFail) error("private package lookup exception")
            return JsonArray(emptyList())
        }
    }
    private fun registry(port: Port, mutex: Mutex = Mutex()) = OperationRegistry().also {
        GenericControlOperations(GenericControl(port, { true }), port, mutex) { OperationReadiness() }.register(it)
    }
    @Test fun descriptorsKeepQueriesSeparateAndRejectCoordinates() {
        val registry = registry(Port())
        assertTrue(registry.get(GenericControlOperations.OBSERVE)!!.descriptor.readOnly)
        val action = registry.get(GenericControlOperations.ACTION)!!.descriptor
        assertFalse(action.readOnly)
        assertNotNull(OperationSchemaValidator.validate(action.inputSchema, buildJsonObject {
            put("observation_id", "id"); put("action", "click"); put("x", 1); put("y", 2)
        }))
    }
    @Test fun genericAndDedicatedHandlersShareTheSameMutex() = runBlocking {
        val mutex = Mutex(locked = true); val registry = registry(Port(), mutex)
        val busy = registry.get(GenericControlOperations.OBSERVE)!!.handler.invoke(OperationInvocation("test", buildJsonObject {}))
        assertFalse(busy.success); assertEquals("ui_busy", busy.summary)
        mutex.unlock()
        assertTrue(registry.get(GenericControlOperations.OBSERVE)!!.handler.invoke(OperationInvocation("test", buildJsonObject {})).success)
    }
    @Test fun sameRequestIdDoesNotRepeatNativeClick() = runBlocking {
        val port = Port(); val registry = registry(port)
        val storeFile = File.createTempFile("generic-tasks", ".json").apply { delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val dispatcher = OperationDispatcher(registry, OperationTaskStore(storeFile), scope)
            val observed = registry.get(GenericControlOperations.OBSERVE)!!.handler.invoke(OperationInvocation("observe", buildJsonObject {}))
            val descriptor = registry.get(GenericControlOperations.ACTION)!!.descriptor
            val args = buildJsonObject {
                put("operation_id", descriptor.id); put("operation_version", descriptor.version); put("schema_hash", descriptor.schemaHash)
                put("request_id", "single-click"); put("input", buildJsonObject {
                    put("observation_id", observed.data.getValue("observation_id")); put("action", "click"); put("node_id", 1)
                })
            }
            val task = dispatcher.execute(args).getValue("task_id")
            withTimeout(2000) {
                while (dispatcher.getTask(buildJsonObject { put("task_id", task) })["state"]?.jsonPrimitive?.content != "succeeded") delay(10)
            }
            assertEquals(task, dispatcher.execute(args)["task_id"]); assertEquals(1, port.writes)
        } finally { scope.cancel(); storeFile.delete() }
    }
    @Test fun latestUnresolvedDedicatedSendRemainsProtectedAfterRestart() {
        val file = File.createTempFile("question-ledger", ".json").apply { delete() }
        try {
            val store = OperationTaskStore(file)
            fun task(id: String, time: Long, state: String) = buildJsonObject {
                put("task_id", id); put("request_id", id); put("operation_id", "gemini.question.ask")
                put("created_at_ms", time); put("state", state)
            }
            store.put(task("unknown", 1, "outcome_unknown"))
            assertTrue(OperationTaskStore(file).latestOutcomeUnknown("gemini.question.ask"))
            store.put(task("confirmed", 2, "succeeded"))
            assertFalse(store.latestOutcomeUnknown("gemini.question.ask"))
        } finally { file.delete() }
    }
    @Test fun appLookupFailureReturnsSafeReasonInsteadOfEscapingTheReadHandler() = runBlocking {
        val port = Port().apply { appsFail = true }
        val result = registry(port).get(GenericControlOperations.APPS)!!.handler.invoke(OperationInvocation("apps", buildJsonObject { put("filter", "Example") }))
        assertFalse(result.success)
        assertEquals("apps_query_failed", result.data["reason_code"]?.jsonPrimitive?.content)
        assertFalse(result.toString().contains("private package"))
    }
}
