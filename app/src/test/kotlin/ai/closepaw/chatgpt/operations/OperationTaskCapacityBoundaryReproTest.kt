package ai.closepaw.chatgpt.operations

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #219: production Task Store boundary reproduction, no phone/private ledger data. */
class OperationTaskCapacityBoundaryReproTest {
    @get:Rule val temp = TemporaryFolder()

    private fun task(i: Int): JsonObject = buildJsonObject {
        put("task_id", "synthetic-$i")
        put("request_id", "synthetic-request-$i")
        put("fingerprint", "synthetic-fingerprint-$i")
        put("operation_id", "linux.shell.exec")
        put("state", if (i == 0) "outcome_unknown" else "succeeded")
        put("result", buildJsonObject { put("stdout", "retained-$i") })
    }

    private fun createLedger(n: Int): File {
        val file = File(temp.root, "ledger-$n.json")
        file.writeText(JsonArray((0 until n).map(::task)).toString())
        return file
    }

    private fun hash(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun newAdmissionSucceedsBeyond2000WithoutRewritingLegacy() {
        val almostFull = createLedger(OperationTaskStore.MAX_TASKS - 1)
        val writable = OperationTaskStore(almostFull)
        val next = task(OperationTaskStore.MAX_TASKS)
        assertTrue(writable.claim(next).created)
        assertEquals(next, writable.get("synthetic-2000"))

        val full = createLedger(OperationTaskStore.MAX_TASKS)
        val before = hash(full)
        val store = OperationTaskStore(full)
        val candidate = task(2001)
        val admitted = store.claim(candidate)
        assertTrue(admitted.created)
        assertEquals(candidate, store.get("synthetic-2001"))
        assertEquals(before, hash(full))
        val restarted = OperationTaskStore(full)
        assertEquals(candidate, restarted.get("synthetic-2001"))
        assertFalse(restarted.claim(candidate).created)
        assertEquals(task(0), store.get("synthetic-0"))
        assertEquals(task(1999), store.get("synthetic-1999"))
        assertFalse(File(full.path + ".tmp").exists())
    }

    @Test fun fullLedgerStillReturnsRetainedDedupWithoutNewExecution() {
        val full = createLedger(OperationTaskStore.MAX_TASKS)
        val before = hash(full)
        val store = OperationTaskStore(full)
        val existing = store.claim(task(5))
        assertFalse(existing.created)
        assertFalse(existing.conflict)
        assertEquals(task(5), existing.task)
        val conflicting = JsonObject(task(5) + ("fingerprint" to JsonPrimitive("different")))
        val conflict = store.claim(conflicting)
        assertFalse(conflict.created)
        assertTrue(conflict.conflict)
        assertEquals(task(5), conflict.task)
        assertEquals(before, hash(full))
        assertEquals("outcome_unknown", store.get("synthetic-0")?.get("state")?.jsonPrimitive?.content)
    }

    @Test fun fileSizeAloneIsNotAdmissionCapacity() {
        // One 12.9MB-class retained stdout, but only one task.
        val file = File(temp.root, "large-one-task.json")
        val large = JsonObject(task(0) + ("result" to buildJsonObject {
            put("stdout", "x".repeat(12_900_000))
        }))
        file.bufferedWriter().use { it.write(JsonArray(listOf(large)).toString()) }
        assertTrue(file.length() > 12_900_000)
        assertTrue(file.length() < OperationTaskDiagnostics.MAX_BYTES)
        val store = OperationTaskStore(file)
        assertTrue(store.claim(task(1)).created)
        assertEquals(large, store.get("synthetic-0"))
    }
}
