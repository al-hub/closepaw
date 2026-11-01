package ai.closepaw.chatgpt.operations

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Synthetic counterexamples, not a reproduction of the private handset ledger.
 * Equal file sizes intentionally exercise independent diagnostic/admission limits.
 * Existing diagnostics/concurrency tests cover malformed input and injected I/O failures.
 */
class OperationTaskStoreInvestigationTest {
    @get:Rule val directory = TemporaryFolder()

    private fun record(index: Int, longRequest: Boolean = false): JsonObject = buildJsonObject {
        put("task_id", UUID(0L, index.toLong() + 1L).toString())
        val request = "synthetic-request-$index"
        put("request_id", if (longRequest) request.padEnd(128, 'x') else request)
        put("fingerprint", "synthetic-fingerprint")
        put("operation_id", "synthetic.test")
        put("operation_version", 1)
        put("schema_hash", "synthetic-schema")
        put("created_at_ms", index.toLong())
        put("state", "succeeded")
        put("result", buildJsonObject { put("stdout", "synthetic retained result $index"); put("stderr", "") })
    }

    /** Stream a large ignored string into the last record, retaining no 13MB fixture string. */
    private fun fixture(
        name: String,
        count: Int = 1,
        longRequest: Boolean = false,
        extraField: String? = null,
    ): File {
        val file = File(directory.root, "$name.json")
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.write("[")
            repeat(count) { index ->
                if (index > 0) out.write(",")
                val raw = record(index, longRequest).toString()
                out.write(raw.dropLast(1))
                if (index == 0 && extraField != null) { out.write(","); out.write(extraField) }
                if (index == count - 1) out.write(",\"padding\":\"") else out.write("}")
            }
        }
        val suffix = "\"}]".toByteArray(Charsets.UTF_8)
        val remaining = TARGET_BYTES - file.length() - suffix.size
        check(remaining > 0)
        FileOutputStream(file, true).buffered().use { out ->
            val buffer = ByteArray(8192) { 'x'.code.toByte() }
            var left = remaining
            while (left > 0) {
                val size = minOf(left, buffer.size.toLong()).toInt()
                out.write(buffer, 0, size)
                left -= size
            }
            out.write(suffix)
        }
        check(file.length() == TARGET_BYTES)
        return file
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                hash.update(buffer, 0, size)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun assertNewAdmissionAndPreservation(file: File, expectedReason: String, originalCount: Int) {
        assertEquals(TARGET_BYTES, file.length())
        val beforeHash = digest(file)
        val diagnostics = OperationTaskDiagnostics(file).snapshot()
        assertEquals(expectedReason, diagnostics["reason_code"]?.jsonPrimitive?.content)
        assertEquals(if (expectedReason == "snapshot_readable") "readable" else "bounded_refusal",
            diagnostics["read_state"]?.jsonPrimitive?.content)
        assertEquals(beforeHash, digest(file))
        assertFalse(File(file.path + ".lock").exists())
        if (expectedReason == "diagnostic_structure_limit") assertNull(diagnostics["task_count"])
        val originals = Json.parseToJsonElement(file.readText()).jsonArray.toList()
        assertEquals(originalCount, originals.size)
        val store = OperationTaskStore(file)
        val candidate = JsonObject(record(10_000) + ("state" to JsonPrimitive("queued")))
        assertTrue(store.claim(candidate).created)
        val id = candidate.getValue("task_id").jsonPrimitive.content
        assertTrue(store.updateActive(id, mapOf(
            "state" to JsonPrimitive("succeeded"),
            "result" to buildJsonObject { put("stdout", "synthetic new completion"); put("stderr", "") },
        )))
        val after = Json.parseToJsonElement(file.readText()).jsonArray
        assertEquals(originalCount + 1, after.size)
        assertEquals(originals, after.dropLast(1))
        assertEquals("succeeded", after.last().jsonObject["state"]?.jsonPrimitive?.content)
        assertEquals("synthetic new completion", after.last().jsonObject["result"]?.jsonObject?.get("stdout")?.jsonPrimitive?.content)
        val completedHash = digest(file)
        for (original in listOf(originals.first(), originals.last()).distinct()) {
            val prior = original.jsonObject
            val dedup = store.claim(prior)
            assertFalse(dedup.created)
            assertFalse(dedup.conflict)
            assertEquals(prior, dedup.task)
            assertEquals(prior, store.byRequest(prior.getValue("request_id").jsonPrimitive.content))
        }
        assertEquals(completedHash, digest(file))
    }

    @Test fun syntheticExactSizeLargeStringRemainsReadableAndAdmitsNewTask() {
        assertNewAdmissionAndPreservation(fixture("large-string"), "snapshot_readable", 1)
    }

    @Test fun syntheticExactSizeNodeRefusalDoesNotImplyAdmissionFailure() {
        val nodes = "\"synthetic_nodes\":[" + "0,".repeat(262_144) + "0]"
        assertNewAdmissionAndPreservation(fixture("nodes", extraField = nodes), "diagnostic_structure_limit", 1)
    }

    @Test fun syntheticExactSizeDepthRefusalDoesNotImplyAdmissionFailure() {
        // Array is depth1, task is depth2, first nested array is depth3; leaf is depth33.
        val depth = "\"synthetic_depth\":" + "[".repeat(30) + "0" + "]".repeat(30)
        assertNewAdmissionAndPreservation(fixture("depth", extraField = depth), "diagnostic_structure_limit", 1)
    }

    @Test fun syntheticExactSizeKeyRefusalDoesNotImplyAdmissionFailure() {
        val key = "\"" + "k".repeat(4097) + "\":0"
        assertNewAdmissionAndPreservation(fixture("key", extraField = key), "diagnostic_structure_limit", 1)
    }

    @Test fun syntheticExactSizeAggregateIdentityRefusalDoesNotImplyAdmissionFailure() {
        assertNewAdmissionAndPreservation(
            fixture("identity", count = 1516, longRequest = true), "diagnostic_structure_limit", 1516,
        )
    }

    @Test fun syntheticExactSizeCapacityFailurePreservesEveryOriginalRecordAndDedup() {
        val file = fixture("capacity", count = OperationTaskStore.MAX_TASKS)
        assertEquals(TARGET_BYTES, file.length())
        val beforeHash = digest(file)
        val diagnostics = OperationTaskDiagnostics(file).snapshot()
        assertEquals("capacity_reached", diagnostics["reason_code"]?.jsonPrimitive?.content)
        assertEquals(OperationTaskStore.MAX_TASKS, diagnostics["task_count"]?.jsonPrimitive?.int)
        val store = OperationTaskStore(file)
        try {
            store.claim(JsonObject(record(10_000) + ("state" to JsonPrimitive("queued"))))
            fail("The unchanged production store must reject a new identity at its capacity")
        } catch (failure: OperationTaskStorageException) {
            assertEquals("capacity_check", failure.stage)
        }
        assertEquals(beforeHash, digest(file))
        val originals = Json.parseToJsonElement(file.readText()).jsonArray
        for (original in listOf(originals.first(), originals.last())) {
            val prior = original.jsonObject
            assertEquals(prior, store.get(prior.getValue("task_id").jsonPrimitive.content))
            val dedup = store.claim(prior)
            assertFalse(dedup.created)
            assertFalse(dedup.conflict)
            assertEquals(prior, dedup.task)
        }
        assertEquals(beforeHash, digest(file))
    }

    private companion object { const val TARGET_BYTES = 12_901_900L }
}
