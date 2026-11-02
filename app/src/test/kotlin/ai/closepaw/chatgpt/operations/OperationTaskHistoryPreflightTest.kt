package ai.closepaw.chatgpt.operations

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OperationTaskHistoryPreflightTest {
    private fun task(id: String, request: String?, state: String) = buildJsonObject {
        put("task_id", id)
        if (request != null) put("request_id", request)
        put("state", state)
        put("result", buildJsonObject { put("stdout", "PRIVATE_RESULT") })
    }

    @Test fun classifiesRetainableVersusUnknownWithoutChangingInput() {
        val records = listOf(task("a", "ra", "succeeded"), task("b", "rb", "outcome_unknown"), task("c", null, "running"))
        val before = JsonArray(records).toString()
        val report = OperationTaskHistoryPreflight.inspect(records)
        assertEquals(3, report.count)
        assertEquals(1, report.retainedTerminal)
        assertEquals(2, report.mutableOrUnknown)
        assertEquals(2, report.requestIdentities)
        assertEquals(before, JsonArray(records).toString())
    }

    @Test fun duplicateTaskOrRequestIdentityFailsClosed() {
        for (records in listOf(
            listOf(task("a", "ra", "succeeded"), task("a", "rb", "failed")),
            listOf(task("a", "ra", "succeeded"), task("b", "ra", "failed"))
        )) {
            try {
                OperationTaskHistoryPreflight.inspect(records)
                fail("duplicate identity should block migration")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun nonStringStateAndIdentityFailClosed() {
        val invalid = listOf(
            buildJsonObject { put("task_id", 3); put("state", "succeeded") },
            buildJsonObject { put("task_id", "a"); put("state", true) },
            buildJsonObject { put("task_id", "a"); put("request_id", false); put("state", "succeeded") }
        )
        invalid.forEach { record ->
            try {
                OperationTaskHistoryPreflight.inspect(listOf(record))
                fail("invalid record should not be eligible for migration")
            } catch (_: IllegalArgumentException) {}
        }
    }
}
