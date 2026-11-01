package ai.closepaw.chatgpt.operations

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OperationTaskResultRetentionTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun onlyConfirmedDurableTerminalShellResultsReleaseLinks() {
        val store = OperationTaskStore(File(directory.root, "tasks"))
        val states = listOf("succeeded", "failed", "timed_out", "cancelled", "interrupted", "running", "queued", "outcome_unknown")
        states.forEach { state ->
            store.put(task(state, state))
            store.put(task("unknown-$state", state, unknown=true))
        }
        val retained = store.retainedShellLinks()
        assertEquals(setOf("succeeded", "failed", "timed_out", "cancelled", "interrupted"), retained.keys)
        assertNotNull(store.get("running"))
        assertNotNull(store.get("unknown-succeeded"))
    }
    @Test fun missingOutputWrongOperationInvalidOutputAndUnconfirmedCancelAreProtected() {
        val store = OperationTaskStore(File(directory.root, "tasks"))
        val good = task("good", "succeeded")
        val result = good.getValue("result").jsonObject
        store.put(JsonObject(good + ("result" to JsonObject(result - "stdout"))))
        assertTrue(store.retainedShellLinks().isEmpty())
        store.put(JsonObject(good + ("operation_id" to JsonPrimitive("other.operation"))))
        assertTrue(store.retainedShellLinks().isEmpty())
        store.put(JsonObject(good + ("output_validation_failed" to JsonPrimitive(true))))
        assertTrue(store.retainedShellLinks().isEmpty())
        val cancelled = task("cancel", "cancelled")
        val cancelledResult = cancelled.getValue("result").jsonObject
        val data = cancelledResult.getValue("data").jsonObject
        store.put(JsonObject(cancelled + ("result" to JsonObject(cancelledResult + ("data" to JsonObject(data - "cancel_confirmed"))))))
        assertTrue(store.retainedShellLinks().isEmpty())
    }
    private fun task(id: String, state: String, unknown: Boolean = false) = buildJsonObject {
        put("task_id", id); put("request_id", id); put("operation_id", "linux.shell.exec"); put("state", state)
        put("result", buildJsonObject {
            put("status", if (state == "succeeded") "succeeded" else "failed")
            put("stdout", "preserved output"); put("stderr", "preserved stderr"); put("exit_code", 0)
            put("data", buildJsonObject {
                put("job_id", "job-$id"); put("state", state); put("outcome_unknown", unknown)
                put("cancel_confirmed", state == "cancelled")
            })
        })
    }
}
