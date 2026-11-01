package ai.closepaw.chatgpt.operations

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #219 intentional RED gate. Must fail against legacy implementation; do not merge as green. */
class OperationTaskRetainedHistoryRedTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun afterImportingFullLegacyHistoryCanAdmitNewTaskWithoutLosingResultsOrDedup() {
        val file = File(temp.root, "legacy.json")
        val original = (0 until OperationTaskStore.MAX_TASKS).map { i -> buildJsonObject {
            put("task_id", "old-$i"); put("request_id", "req-$i")
            put("fingerprint", "fingerprint-$i")
            put("operation_id", "linux.shell.exec")
            put("state", if (i == 0) "outcome_unknown" else "succeeded")
            put("result", buildJsonObject { put("stdout", "retained-output-$i") })
        } }
        file.writeText(JsonArray(original).toString())
        val store = OperationTaskStore(file)
        val next = buildJsonObject {
            put("task_id", "new-task"); put("request_id", "new-request")
            put("fingerprint", "fresh"); put("state", "queued")
        }
        // Deliberately red: current production store refuses capacity_check here.
        val claim = store.claim(next)
        assertTrue(claim.created)
        original.forEachIndexed { i, saved ->
            assertEquals(saved, store.get("old-$i"))
            assertEquals(saved, store.byRequest("req-$i"))
            assertFalse(store.claim(saved).created)
        }
        assertEquals("outcome_unknown", store.get("old-0")?.get("state")?.jsonPrimitive?.content)
        assertEquals(next, store.get("new-task"))
    }
}
