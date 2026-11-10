package ai.closepaw.chatgpt.operations

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OperationTaskHistoricalDiagnosticRegressionTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun validHistoryIsNotRejectedByCumulativePayloadNodeBudget() {
        val file = File(directory.root, "tasks.json")
        file.bufferedWriter().use { out ->
            out.write("[")
            repeat(2000) { i ->
                if (i > 0) out.write(",")
                out.write("{\"task_id\":\"id-$i\",\"state\":\"succeeded\",\"payload\":[")
                out.write(List(150) { "0" }.joinToString(","))
                out.write("]}")
            }
            out.write("]")
        }
        val before = file.readBytes()
        val result = OperationTaskDiagnostics(file).snapshot()
        assertEquals("readable", result["read_state"]?.jsonPrimitive?.content)
        assertEquals(2000, result["task_count"]?.jsonPrimitive?.int)
        assertArrayEquals(before, file.readBytes())
        assertFalse(File(file.path + ".lock").exists())
    }

    @Test fun historicalCountIsNotTheLegacyAdmissionQuota() {
        val file = File(directory.root, "tasks.json")
        file.writeText(JsonArray((0..2000).map { i -> buildJsonObject {
            put("task_id", "id-$i"); put("state", "succeeded")
        } }).toString())
        val result = OperationTaskDiagnostics(file).snapshot()
        assertEquals("readable", result["read_state"]?.jsonPrimitive?.content)
        assertEquals(2001, result["task_count"]?.jsonPrimitive?.int)
        assertEquals("not_tested", result["write_health"]?.jsonPrimitive?.content)
    }
}
