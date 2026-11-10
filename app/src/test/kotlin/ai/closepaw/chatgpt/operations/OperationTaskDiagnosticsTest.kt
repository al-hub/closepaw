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
                put("fingerprint", "private-hash"); put("operation_id", "test")
                put("operation_version", 1); put("schema_hash", "private-schema")
                put("created_at_ms", 1L); put("summary", "private-summary")
                put("state", if (i == 0) "outcome_unknown" else if (i == 1) "running" else "succeeded")
                put("result", buildJsonObject { put("stdout", "SENSITIVE_OUTPUT") })
            }
        }).toString())
        val before = file.readBytes()
        val result = OperationTaskDiagnostics(file).snapshot()
        assertEquals("legacy_capacity_reached", result["reason_code"]?.jsonPrimitive?.content)
        assertEquals(2000, result["task_count"]?.jsonPrimitive?.int)
        assertEquals(1, result["active_count"]?.jsonPrimitive?.int)
        assertEquals(1, result["unknown_count"]?.jsonPrimitive?.int)
        assertEquals(1998, result["terminal_count"]?.jsonPrimitive?.int)
        assertFalse(result.toString().contains("private-"))
        assertFalse(result.toString().contains("SENSITIVE_OUTPUT"))
        assertArrayEquals(before, file.readBytes())
        assertFalse(File(file.path + ".lock").exists())
    }
    @Test fun measuredLargeLedgerTierRemainsReadOnlyAndRedacted() {
        val file = ledger()
        file.parentFile!!.mkdirs()
        // Approximately the measured 12.9MB phone snapshot, with a bounded
        // count and structure. No real task IDs or result contents may escape.
        val secret = "SENSITIVE_OUTPUT_" + "x".repeat(65000)
        file.bufferedWriter().use { out ->
            out.write("[")
            repeat(200) { i ->
                if (i > 0) out.write(",")
                out.write(buildJsonObject {
                    put("task_id", "private-$i"); put("request_id", "secret-$i")
                    put("state", if (i == 0) "outcome_unknown" else "succeeded")
                    put("result", buildJsonObject { put("stdout", secret) })
                }.toString())
            }
            out.write("]")
        }
        val beforeHash = digest(file)
        assertTrue(file.length() > 8L * 1024 * 1024)
        assertTrue(file.length() < OperationTaskDiagnostics.MAX_BYTES)
        val beforeLength = file.length()
        val result = OperationTaskDiagnostics(file).snapshot()
        assertEquals("readable", result["read_state"]?.jsonPrimitive?.content)
        assertEquals(200, result["task_count"]?.jsonPrimitive?.int)
        assertEquals(1, result["unknown_count"]?.jsonPrimitive?.int)
        assertEquals(199, result["terminal_count"]?.jsonPrimitive?.int)
        assertFalse(result.toString().contains("SENSITIVE_OUTPUT"))
        assertFalse(result.toString().contains("private-"))
        assertEquals(beforeLength, file.length())
        assertEquals(beforeHash, digest(file))
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
    @Test fun deepOrExcessivePayloadStructureIsRejectedWithoutDomParsing() {
        val file = ledger()
        file.parentFile!!.mkdirs()
        for (raw in listOf("[{\"task_id\":\"a\",\"x\":" + "[".repeat(10000) + "]".repeat(10000) + "}]", "[{\"task_id\":\"a\",\"x\":[" + "0,".repeat(300000) + "0]}]")) {
            file.writeText(raw)
            assertEquals("diagnostic_structure_limit", OperationTaskDiagnostics(file).snapshot()["reason_code"]?.jsonPrimitive?.content)
            assertEquals(raw, file.readText())
        }
    }
    private fun digest(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    @Test fun strictJsonSyntaxUtf8DuplicateFieldsAndIdentityBoundsFailClosed() {
        val file = ledger(); file.parentFile!!.mkdirs()
        val invalid = listOf(
            "[{\"task_id\":\"a\",\"task_id\":\"b\"}]",
            "[{\"task_id\":\"a\",\"request_id\":\"r\"},{\"task_id\":\"b\",\"request_id\":\"r\"}]",
            "[{\"task_id\":1}]", "[{\"task_id\":\"a\",\"state\":true}]",
            "[{\"task_id\":\"a\",\"x\":01}]", "[{\"task_id\":\"a\",\"x\":1e}]",
            "[{\"task_id\":\"a\",\"x\":+1}]", "[{\"task_id\":\"a\",\"x\":NaN}]",
            "[{\"task_id\":\"a\",\"x\":\"bad\\q\"}]", "[] trailing", "[{},]",
            "[{\"task_id\":\"a\",\"x\":\"bad\\u１２３４\"}]",
            "[{\"task_id\":\"a\",\"x\":\"line\nfeed\"}]"
        )
        invalid.forEach { raw ->
            file.writeText(raw)
            assertEquals(raw, "invalid_ledger", OperationTaskDiagnostics(file).snapshot()["reason_code"]?.jsonPrimitive?.content)
            assertEquals(raw, file.readText())
        }
        file.writeBytes("[{\"task_id\":\"".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}]".toByteArray())
        val hash = digest(file)
        assertEquals("invalid_ledger", OperationTaskDiagnostics(file).snapshot()["reason_code"]?.jsonPrimitive?.content)
        assertEquals(hash, digest(file))
        file.writeText("[{\"task_id\":\"" + "x".repeat(4097) + "\"}]")
        assertEquals("diagnostic_structure_limit", OperationTaskDiagnostics(file).snapshot()["reason_code"]?.jsonPrimitive?.content)
    }
    @Test fun streamedPayloadGrammarAndStateClassification() {
        val file = ledger(); file.parentFile!!.mkdirs()
        val states = listOf("queued", "running", "cancel_requested", "succeeded", "failed", "cancelled", "timed_out", "interrupted", "outcome_unknown", "future")
        file.writeText(JsonArray(states.mapIndexed { i, state -> buildJsonObject {
            put("task_id", "id-$i"); put("state", state)
            put("payload", Json.parseToJsonElement("""{"escaped":"\u0041\n\\\"","values":[null,true,false,-0,1.5,2e+3,{}]}"""))
        } }).toString())
        val result = OperationTaskDiagnostics(file).snapshot()
        assertEquals("readable", result["read_state"]?.jsonPrimitive?.content)
        assertEquals(3, result["active_count"]?.jsonPrimitive?.int)
        assertEquals(5, result["terminal_count"]?.jsonPrimitive?.int)
        assertEquals(2, result["unknown_count"]?.jsonPrimitive?.int)
        assertTrue(result.toString().length < 1024)
    }
    @Test fun exactByteBoundaryAndGrowingOrReplacedSnapshotAreBounded() {
        val file = ledger(); file.parentFile!!.mkdirs()
        file.outputStream().buffered().use { out ->
            out.write("[]".toByteArray())
            val padding = ByteArray(8192) { 32 }
            var remaining = OperationTaskDiagnostics.MAX_BYTES - 2
            while (remaining > 0) { val n = minOf(remaining, padding.size); out.write(padding, 0, n); remaining -= n }
        }
        assertEquals("readable", OperationTaskDiagnostics(file).snapshot()["read_state"]?.jsonPrimitive?.content)
        file.appendText(" ")
        assertEquals("ledger_too_large", OperationTaskDiagnostics(file).snapshot()["reason_code"]?.jsonPrimitive?.content)
        file.writeText("[]")
        val changed = OperationTaskDiagnostics(file, openInput = {
            object : java.io.FilterInputStream(file.inputStream()) {
                override fun close() { super.close(); file.appendText(" ") }
            }
        }).snapshot()
        assertEquals("ledger_changed", changed["reason_code"]?.jsonPrimitive?.content)
        assertFalse(changed.containsKey("task_count"))
        file.writeText("[]")
        val replaced = OperationTaskDiagnostics(file, openInput = {
            val stream = file.inputStream()
            val replacement = File(file.parentFile, "replacement")
            replacement.writeText("[ ]")
            java.nio.file.Files.move(replacement.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            stream
        }).snapshot()
        assertEquals("ledger_changed", replaced["reason_code"]?.jsonPrimitive?.content)
        // Initial stat fits, but the descriptor grows before scanning: enforce the streaming byte bound too.
        file.writeText("[]")
        val growing = OperationTaskDiagnostics(file, openInput = {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(OperationTaskDiagnostics.MAX_BYTES + 1L) }
            // All bytes are JSON whitespace, so refusal must come from the byte counter.
            java.io.SequenceInputStream("[]".byteInputStream(), object : java.io.InputStream() {
                var left = OperationTaskDiagnostics.MAX_BYTES
                override fun read(): Int = if (left-- > 0) 32 else -1
            })
        }).snapshot()
        assertEquals("ledger_too_large", growing["reason_code"]?.jsonPrimitive?.content)
    }
    @Test fun recordDepthNodeAndRetainedIdentityBudgetsAreIndependent() {
        val file = ledger(); file.parentFile!!.mkdirs()
        file.bufferedWriter().use { out ->
            out.write("[")
            repeat(2001) { i -> if (i > 0) out.write(","); out.write("{\"task_id\":\"$i\"}") }
            out.write("]")
        }
        assertEquals(2001, OperationTaskDiagnostics(file).snapshot()["task_count"]?.jsonPrimitive?.int)
        file.writeText("[{\"task_id\":\"a\",\"x\":" + "[".repeat(30) + "0" + "]".repeat(30) + "}]")
        assertEquals("diagnostic_structure_limit", OperationTaskDiagnostics(file).snapshot()["reason_code"]?.jsonPrimitive?.content)
        file.writeText("[{\"task_id\":\"a\",\"x\":" + "[".repeat(29) + "0" + "]".repeat(29) + "}]")
        assertEquals("readable", OperationTaskDiagnostics(file).snapshot()["read_state"]?.jsonPrimitive?.content)
        file.bufferedWriter().use { out ->
            out.write("[{\"task_id\":\"a\",\"x\":[")
            repeat(OperationTaskDiagnosticScanner.MAX_NODES) { i -> if (i > 0) out.write(","); out.write("0") }
            out.write("]}]")
        }
        assertEquals("diagnostic_structure_limit", OperationTaskDiagnostics(file).snapshot()["reason_code"]?.jsonPrimitive?.content)
        file.bufferedWriter().use { out ->
            out.write("[")
            repeat(100) { i -> if (i > 0) out.write(","); out.write("{\"task_id\":\"$i" + "x".repeat(3000) + "\"}") }
            out.write("]")
        }
        assertEquals(100, OperationTaskDiagnostics(file).snapshot()["task_count"]?.jsonPrimitive?.int)
    }

}
