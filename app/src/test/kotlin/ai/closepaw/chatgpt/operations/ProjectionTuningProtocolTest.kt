package ai.closepaw.chatgpt.operations

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectionTuningProtocolTest {
    private fun input() = buildJsonObject {
        put("expected_revision", 0); put("timeout_ms", 1500); put("poll_ms", 25)
        put("authorized", true); put("session_id", "actual-server-session")
    }
    @Test fun `actual production descriptors register and query cannot mutate tuning`() = runTest {
        val registry = OperationRegistry()
        val get = ProjectionTuningProtocol.get()
        val set = ProjectionTuningProtocol.set()
        var writes = 0
        registry.register(get, object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation) = OperationResult()
        })
        registry.register(set, object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation): OperationResult { writes++; return OperationResult() }
        })
        assertTrue(get.readOnly); assertFalse(set.readOnly)
        val file = File.createTempFile("projection-protocol", ".json").apply { delete() }
        try {
            val dispatcher = OperationDispatcher(registry, OperationTaskStore(file), this)
            val args = buildJsonObject {
                put("operation_id", set.id); put("operation_version", set.version)
                put("schema_hash", set.schemaHash); put("input", input()); put("request_id", "polling-change")
            }
            assertEquals("write_operation_requires_execute", dispatcher.query(args)["error_code"]!!.jsonPrimitive.content)
            assertEquals(0, writes)
            assertEquals("accepted", dispatcher.execute(args)["status"]!!.jsonPrimitive.content)
            testScheduler.runCurrent(); assertEquals(1, writes)
            // Request-id idempotence is maintained for this real write descriptor.
            dispatcher.execute(args); testScheduler.runCurrent(); assertEquals(1, writes)
        } finally { file.delete() }
    }
    @Test fun `queued tuning cannot run under a later session epoch`() = runTest {
        val registry = OperationRegistry(); val set = ProjectionTuningProtocol.set()
        var epoch = 1L; var writes = 0
        registry.register(set, object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation): OperationResult { writes++; return OperationResult() }
        })
        val file = File.createTempFile("projection-epoch", ".json").apply { delete() }
        try {
            val dispatcher = OperationDispatcher(registry, OperationTaskStore(file), this,
                operationEpoch = { if (ProjectionTuningProtocol.requiresSessionEpoch(it)) epoch else 0L })
            val accepted = dispatcher.execute(buildJsonObject {
                put("operation_id", set.id); put("operation_version", set.version); put("schema_hash", set.schemaHash)
                put("request_id", "epoch-test"); put("input", input())
            })
            epoch++ // pause/resume under same ID must still invalidate queued approval
            testScheduler.runCurrent()
            val result = dispatcher.getTask(buildJsonObject { put("task_id", accepted.getValue("task_id")) })
            assertEquals("failed", result["state"]!!.jsonPrimitive.content)
            assertEquals("control_session_changed_before_start", result["summary"]!!.jsonPrimitive.content)
            assertEquals(0, writes)
        } finally { file.delete() }
    }
    @Test fun `trusted execute admission epoch survives the pre-handler check race`() = runTest {
        val session = ai.closepaw.chatgpt.control.ControlSession().apply { start() }
        val admitted = session.epoch
        var writes = 0
        var handlerCalled = false
        val persistence = object : ai.closepaw.platform.virtualdisplay.ProjectionTuningPersistence {
            override fun load(): ai.closepaw.platform.virtualdisplay.ProjectionTuningValues? = null
            override fun commit(values: ai.closepaw.platform.virtualdisplay.ProjectionTuningValues): ai.closepaw.platform.virtualdisplay.ProjectionTuningOutcome {
                writes++; return ai.closepaw.platform.virtualdisplay.ProjectionTuningOutcome.APPLIED
            }
        }
        val state = ai.closepaw.platform.virtualdisplay.ProjectionTuningState(persistence)
        val registry = OperationRegistry(); val set = ProjectionTuningProtocol.set()
        registry.register(set, object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation): OperationResult {
                handlerCalled = true
                assertEquals(admitted, invocation.admittedEpoch)
                val outcome = state.update(session, session.id, true, 0, 2000, 50, invocation.admittedEpoch!!)
                assertEquals(ai.closepaw.platform.virtualdisplay.ProjectionTuningOutcome.REFUSED, outcome)
                return OperationResult(success = false)
            }
        })
        var epochReads = 0
        val file = File.createTempFile("projection-race", ".json").apply { delete() }
        try {
            val dispatcher = OperationDispatcher(registry, OperationTaskStore(file), this,
                operationEpoch = {
                    val observed = session.epoch
                    if (++epochReads == 2) {
                        // The pre-start check sees the old epoch, then the user
                        // pauses/resumes before the handler begins.
                        session.pause(); check(session.beginResume()); session.finishResume(session.epoch, true)
                    }
                    observed
                })
            dispatcher.execute(buildJsonObject {
                put("operation_id", set.id); put("operation_version", set.version); put("schema_hash", set.schemaHash)
                put("request_id", "check-race"); put("input", input())
            })
            testScheduler.runCurrent(); assertTrue(handlerCalled); assertEquals(0, writes)
        } finally { file.delete() }
    }
    @Test fun `actual schema rejects missing approval unsafe bounds and extra security fields`() {
        val schema = ProjectionTuningProtocol.set().inputSchema
        OperationSchemaValidator.checkSchema(schema)
        assertNull(OperationSchemaValidator.validate(schema, input()))
        val bad = listOf(
            input() + ("authorized" to JsonPrimitive(false)), input() - "authorized",
            input() + ("timeout_ms" to JsonPrimitive(3001)), input() + ("timeout_ms" to JsonPrimitive(1499)),
            input() + ("poll_ms" to JsonPrimitive(24)), input() + ("poll_ms" to JsonPrimitive(101)),
            input() + ("max_frame_age_ms" to JsonPrimitive(60000)), input() + ("session_id" to JsonPrimitive("")),
        )
        for (value in bad) assertNotNull(OperationSchemaValidator.validate(schema, JsonObject(value)))
    }
}
