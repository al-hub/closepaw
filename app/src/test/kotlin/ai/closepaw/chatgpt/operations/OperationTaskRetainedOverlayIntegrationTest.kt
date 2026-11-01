package ai.closepaw.chatgpt.operations

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Integration checks against the real OperationTaskStore, not only staging. */
class OperationTaskRetainedOverlayIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private fun record(i: Int, state: String = "succeeded") = buildJsonObject {
        put("task_id", "id-$i"); put("request_id", "request-$i")
        put("fingerprint", "fp-$i"); put("operation_id", "linux.shell.exec")
        put("state", state); put("created_at_ms", i.toLong())
        put("result", buildJsonObject { put("stdout", "output-$i") })
    }
    private fun original(): File {
        val file = File(temp.root, "tasks.json")
        file.writeText(JsonArray((0 until 2000).map { record(it, if (it == 0) "outcome_unknown" else "succeeded") }).toString())
        return file
    }
    private fun sha(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun originalPreservedAcrossAdmissionResultAndRestart() {
        val file = original(); val initial = sha(file)
        val store = OperationTaskStore(file)
        assertTrue(store.claim(record(2000, "queued")).created)
        assertTrue(store.updateActive("id-2000", mapOf("state" to JsonPrimitive("succeeded"))))
        assertEquals(initial, sha(file))
        val restarted = OperationTaskStore(file)
        assertEquals("succeeded", restarted.get("id-2000")?.get("state")?.jsonPrimitive?.content)
        assertEquals(record(0, "outcome_unknown"), restarted.get("id-0"))
        assertFalse(restarted.claim(record(2000, "queued")).created)
        assertEquals(record(1999), restarted.byRequest("request-1999"))
        assertEquals(initial, sha(file))
    }

    @Test fun firstOverlayFailureMustNotAdmitAndLegacyRemainsUnchanged() {
        val file = original(); val initial = sha(file)
        val store = OperationTaskStore(file, beforeStage = {
            if (it == "overlay_write") throw java.io.IOException("injected")
        })
        try {
            store.claim(record(2000))
            fail("claim must fail closed")
        } catch (e: OperationTaskStorageException) { assertEquals("overlay_write", e.stage) }
        assertEquals(initial, sha(file))
        assertNull(OperationTaskStore(file).get("id-2000"))
        assertTrue(OperationTaskStore(file).claim(record(2000)).created)
        assertEquals(initial, sha(file))
    }
}
