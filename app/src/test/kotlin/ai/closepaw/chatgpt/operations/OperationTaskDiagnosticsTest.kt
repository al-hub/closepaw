package ai.closepaw.chatgpt.operations

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OperationTaskDiagnosticsTest {
    @get:Rule val directory = TemporaryFolder()
    private fun ledger() = File(directory.root, "mcp/tasks.json")
    @Test fun missingLedgerDiagnosisDoesNotCreateDirectoriesOrLocks() {
        val file = ledger()
        val result = OperationTaskDiagnostics(file).snapshot()
        assertEquals("absent", result["read_state"]?.jsonPrimitive?.content)
        assertFalse(file.parentFile!!.exists())
        assertFalse(File(file.path + ".lock").exists())
    }
    @Test fun countsAndCapacityAreIndependentOfClaimAndNeverExposePayloads() {
        val file = ledger()
        file.parentFile!!.mkdirs()
        file.writeText(JsonArray((0 until OperationTaskStore.MAX_TASKS).map { i ->
            buildJsonObject {
                put("task_id", "private-$i"); put("request_id", "secret-$i")
                put("state", if (i == 0) "outcome_unknown" else if (i == 1) "running" else "succeeded")
                put("result", buildJsonObject { put("stdout", "SENSITIVE_OUTPUT") })
            }
        }).toString())
        val before = file.readBytes()
        val result = OperationTaskDiagnostics(file).snapshot()
        assertEquals("capacity_reached", result["reason_code"]?.jsonPrimitive?.content)
        assertEquals(2000, result["task_count"]?.jsonPrimitive?.int)
        assertEquals(1, result["active_count"]?.jsonPrimitive?.int)
        assertEquals(1, result["unknown_count"]?.jsonPrimitive?.int)
        assertEquals(1998, result["terminal_count"]?.jsonPrimitive?.int)
        assertFalse(result.toString().contains("private-"))
        assertFalse(result.toString().contains("SENSITIVE_OUTPUT"))
        assertArrayEquals(before, file.readBytes())
        assertFalse(File(file.path + ".lock").exists())
    }
    @Test fun malformedDuplicateAndOversizeLedgersFailClosedWithoutReplacement() {
        val file = ledger()
        file.parentFile!!.mkdirs()
        for (raw in listOf("broken", "[{\"task_id\":\"a\"},{\"task_id\":\"a\"}]", "{}")) {
            file.writeText(raw)
            val result = OperationTaskDiagnostics(file).snapshot()
            assertEquals("invalid_ledger", result["reason_code"]?.jsonPrimitive?.content)
            assertEquals(raw, file.readText())
        }
        java.io.RandomAccessFile(file, "rw").use { it.setLength(OperationTaskDiagnostics.MAX_BYTES + 1L) }
        assertEquals("ledger_too_large", OperationTaskDiagnostics(file).snapshot()["reason_code"]?.jsonPrimitive?.content)
        assertEquals(OperationTaskDiagnostics.MAX_BYTES + 1L, file.length())
    }
    @Test fun failedAtomicReplacementReportsStageWithoutLeakingPathOrExceptionMessage() {
        val file = ledger()
        val store = OperationTaskStore(file, beforeStage = { stage ->
            if (stage == "atomic_replace") throw java.nio.file.AtomicMoveNotSupportedException("PRIVATE_PATH", "PRIVATE_PATH", "PRIVATE_ERROR")
        })
        try {
            store.claim(buildJsonObject {
                put("task_id", "new"); put("request_id", "request"); put("fingerprint", "hash"); put("state", "queued")
            })
            fail("replacement should fail")
        } catch (_: Exception) {}
        val result = store.diagnostics()
        assertEquals("atomic_replace", result["last_failure_stage"]?.jsonPrimitive?.content)
        assertEquals("atomic_move_unsupported", result["last_failure_reason"]?.jsonPrimitive?.content)
        assertFalse(result.toString().contains("PRIVATE"))
        assertFalse(file.exists())
        assertTrue(file.parentFile!!.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
    @Test fun corruptStartupRemainsQueryableAndNeverErasesTheLedger() {
        val file = ledger()
        file.parentFile!!.mkdirs()
        file.writeText("corrupt")
        val store = OperationTaskStore(file)
        assertEquals("invalid_ledger", store.diagnostics()["reason_code"]?.jsonPrimitive?.content)
        try { store.byRequest("new"); fail("corruption must block admission") } catch (_: Exception) {}
        assertEquals("corrupt", file.readText())
    }
}
