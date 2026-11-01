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
        val diagnostic = OperationTaskDiagnostics(file)
        try { OperationTaskStore(file, diagnostic = diagnostic); fail("corruption must block startup") }
        catch (_: OperationTaskStorageException) {}
        assertEquals("invalid_ledger", diagnostic.snapshot()["reason_code"]?.jsonPrimitive?.content)
        assertEquals("corrupt", file.readText())
    }
    @Test fun everyPersistenceBoundaryPreservesExistingResultAndDedupOnFailure() {
        for (failureStage in listOf("stage_create", "stage_open", "write", "fsync", "atomic_replace")) {
            val file = File(directory.root, failureStage)
            val original = buildJsonObject {
                put("task_id", "old"); put("request_id", "old-request")
                put("fingerprint", "same"); put("state", "succeeded")
                put("result", buildJsonObject { put("stdout", "preserved") })
            }
            OperationTaskStore(file).put(original)
            val before = file.readBytes()
            val store = OperationTaskStore(file, beforeStage = { stage ->
                if (stage == failureStage) throw java.io.IOException("ENOSPC PRIVATE_PATH")
            })
            try {
                store.claim(buildJsonObject {
                    put("task_id", "new"); put("request_id", "new-request"); put("fingerprint", "same")
                })
                fail("write unexpectedly succeeded")
            } catch (_: OperationTaskStorageException) {}
            assertArrayEquals(before, file.readBytes())
            assertEquals(original, store.get("old"))
            assertFalse(store.claim(original).created)
            assertEquals("no_space", store.diagnostics()["last_failure_reason"]?.jsonPrimitive?.content)
            assertEquals(failureStage, store.diagnostics()["last_failure_stage"]?.jsonPrimitive?.content)
        }
    }
    @Test fun startupReconciliationFailurePreservesUnknownWorkAndDiagnosticEntry() {
        val file = ledger()
        file.parentFile!!.mkdirs()
        file.writeText("""[{"task_id":"uncertain","request_id":"unknown","state":"running","operation_id":"gemini.question.ask"}]""")
        val before = file.readBytes()
        val diagnostic = OperationTaskDiagnostics(file)
        try {
            OperationTaskStore(file, diagnostic = diagnostic, beforeStage = { stage ->
                if (stage == "atomic_replace") throw java.io.IOException("no space left")
            })
            fail("unreconciled store must not be usable")
        } catch (_: OperationTaskStorageException) {}
        assertArrayEquals(before, file.readBytes())
        assertEquals(1, diagnostic.snapshot()["active_count"]?.jsonPrimitive?.int)
        assertTrue(OperationTaskStore(file).latestOutcomeUnknown("gemini.question.ask"))
    }
    @Test fun lockContentionCannotBlockIndependentDiagnosis() {
        val file = ledger()
        file.parentFile!!.mkdirs()
        file.writeText("[]")
        java.io.RandomAccessFile(File(file.path + ".lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                try { OperationTaskStore(file); fail("lock overlap accepted") }
                catch (_: OperationTaskStorageException) {}
                assertEquals("readable", OperationTaskDiagnostics(file).snapshot()["read_state"]?.jsonPrimitive?.content)
            }
        }
    }
    @Test fun deepOrExcessiveStructureIsRejectedBeforeDomParsing() {
        val file = ledger()
        file.parentFile!!.mkdirs()
        for (raw in listOf("[".repeat(10000) + "]".repeat(10000), "[" + "{},".repeat(70000) + "{}]")) {
            file.writeText(raw)
            assertEquals("invalid_ledger", OperationTaskDiagnostics(file).snapshot()["reason_code"]?.jsonPrimitive?.content)
            assertEquals(raw, file.readText())
        }
    }
}
