package ai.closepaw.chatgpt.operations

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OperationTaskSqliteCandidateAndroidTest {
    private fun record(i: Int): JsonObject = buildJsonObject {
        put("task_id", "id-$i")
        put("request_id", "request-$i")
        put("state", "succeeded")
        put("operation_id", "linux.shell.exec")
        put("created_at_ms", i.toLong())
        put("fingerprint", "fp-$i")
        put("result", buildJsonObject { put("stdout", "output-$i") })
        put("future_field", "must-survive")
    }

    @Test fun importAndClaimAboveLegacyLimitRetainsAllFieldsAndDedup() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("task-sqlite-219-", ".db", dir)
        file.delete()
        try {
            val store = OperationTaskSqliteCandidate(file)
            store.importOnce((0 until 2000).map(::record))
            assertEquals(record(0), store.get("id-0"))
            assertEquals(record(1999), store.get("id-1999"))
            assertTrue(store.claim(record(2000)).created)
            assertEquals(record(2000), OperationTaskSqliteCandidate(file).get("id-2000"))
            assertFalse(OperationTaskSqliteCandidate(file).claim(record(2000)).created)
            assertEquals(record(2000), store.byRequest("request-2000"))
            assertTrue(store.update("id-2000", mapOf("summary" to JsonPrimitive("progress"))))
            assertEquals("progress", OperationTaskSqliteCandidate(file).get("id-2000")
                ?.get("summary")?.jsonPrimitive?.content)
            assertFalse(store.update("id-2000", mapOf("state" to JsonPrimitive("queued")), activeOnly = true))
            assertEquals(2001, store.allRecords().size)
            assertEquals(record(0), store.allRecords().first())
            try {
                store.importOnce(listOf(record(2001)))
                fail("committed migration cannot be silently overwritten")
            } catch (_: IllegalArgumentException) {}
        } finally {
            file.delete()
            File(file.path + "-journal").delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
    }
}
