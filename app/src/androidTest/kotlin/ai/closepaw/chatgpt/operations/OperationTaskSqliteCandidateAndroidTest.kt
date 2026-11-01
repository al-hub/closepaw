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

    @Test fun preexistingHistoryAbove2000MigratesWithoutLegacyQuotaAndRemainsQueryable() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val legacy = File.createTempFile("task-overcap-219-", ".json", dir)
        legacy.writeText(JsonArray((0 until 2001).map(::record)).toString(), Charsets.UTF_8)
        val immutableOriginal = legacy.readBytes()
        try {
            val store = OperationTaskStore(legacy)
            assertEquals(record(2000), store.byRequest("request-2000"))
            assertTrue(store.claim(record(2001)).created)
            assertEquals(record(0), store.get("id-0"))
            assertArrayEquals(immutableOriginal, legacy.readBytes())
            val reloaded = OperationTaskStore(legacy, beforeStage = { stage ->
                if (stage == "read" || stage == "decode" || stage == "overlay_read") {
                    throw IllegalStateException("committed SQLite must not reparse legacy JSON")
                }
            })
            assertEquals(record(2001), reloaded.get("id-2001"))
            assertFalse(reloaded.claim(record(2001)).created)
            assertArrayEquals(immutableOriginal, legacy.readBytes())
        } finally {
            legacy.delete()
            File(legacy.path + ".sqlite").delete()
            File(legacy.path + ".sqlite-journal").delete()
            File(legacy.path + ".sqlite-wal").delete()
            File(legacy.path + ".sqlite-shm").delete()
            File(legacy.path + ".sqlite.ready").delete()
            File(legacy.path + ".lock").delete()
        }
    }

    @Test fun postCommitPreActivationCrashRecoversOnlyMatchingOriginal() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val legacy = File.createTempFile("task-midcommit-219-", ".json", dir)
        val records = (0 until 4).map(::record)
        legacy.writeText(JsonArray(records).toString(), Charsets.UTF_8)
        try {
            // Simulate a crash between SQLite's durable commit and .ready publication.
            OperationTaskSqliteCandidate(File(legacy.path + ".sqlite")).importOnce(records)
            assertFalse(File(legacy.path + ".sqlite.ready").exists())
            val store = OperationTaskStore(legacy)
            assertTrue(File(legacy.path + ".sqlite.ready").isFile)
            assertEquals(record(3), store.get("id-3"))
            assertFalse(store.claim(record(3)).created)
        } finally {
            legacy.delete()
            File(legacy.path + ".sqlite").delete()
            File(legacy.path + ".sqlite-journal").delete()
            File(legacy.path + ".sqlite-wal").delete()
            File(legacy.path + ".sqlite-shm").delete()
            File(legacy.path + ".sqlite.ready").delete()
            File(legacy.path + ".lock").delete()
        }
    }

    @Test fun failedImportRollsBackRowsAndCompletionMarker() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("task-rollback-219-", ".db", dir)
        file.delete()
        try {
            val store = OperationTaskSqliteCandidate(file)
            try {
                store.importOnce(listOf(record(0), record(0)))
                fail("duplicate identity must abort the whole import")
            } catch (_: android.database.sqlite.SQLiteConstraintException) {}
            android.database.sqlite.SQLiteDatabase.openDatabase(
                file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                assertEquals(0, db.version)
                db.rawQuery("SELECT COUNT(*) FROM tasks", null).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(0L, rows.getLong(0))
                }
            }
            try {
                store.claim(record(1))
                fail("uncommitted database must not accept new requests")
            } catch (_: IllegalArgumentException) {}
            store.importOnce(listOf(record(0)))
            assertEquals(record(0), store.get("id-0"))
            assertTrue(store.claim(record(1)).created)
        } finally {
            file.delete()
            File(file.path + "-journal").delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
    }

    @Test fun importAndClaimAboveLegacyLimitRetainsAllFieldsAndDedup() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("task-sqlite-219-", ".db", dir)
        file.delete()
        try {
            val store = OperationTaskSqliteCandidate(file)
            try {
                store.claim(record(2000))
                fail("claim before committed migration must be refused")
            } catch (_: IllegalArgumentException) {}
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

    @Test fun realAndroidStoreAdmits2001WithoutCreatingJsonGenerations() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val legacy = File.createTempFile("task-ledger-219-", ".json", dir)
        val oldRecords = (0 until 2000).map(::record)
        legacy.writeText(JsonArray(oldRecords).toString(), Charsets.UTF_8)
        val original = legacy.readBytes()
        try {
            val store = OperationTaskStore(legacy)
            assertTrue(store.claim(record(2000)).created)
            assertFalse(File(legacy.path + ".history").exists())
            assertArrayEquals(original, legacy.readBytes())
            val restarted = OperationTaskStore(legacy)
            assertEquals(record(0), restarted.get("id-0"))
            assertEquals(record(1999), restarted.byRequest("request-1999"))
            assertEquals(record(2000), restarted.get("id-2000"))
            assertFalse(restarted.claim(record(2000)).created)
            assertArrayEquals(original, legacy.readBytes())
        } finally {
            legacy.delete()
            File(legacy.path + ".sqlite").delete()
            File(legacy.path + ".sqlite-journal").delete()
            File(legacy.path + ".sqlite-wal").delete()
            File(legacy.path + ".sqlite-shm").delete()
            File(legacy.path + ".sqlite.ready").delete()
            File(legacy.path + ".lock").delete()
        }
    }

    @Test fun restartPreservesProgressAndMissingCommittedDbRefusesReplay() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val legacy = File.createTempFile("task-restart-219-", ".json", dir)
        legacy.writeText(JsonArray((0 until 2000).map(::record)).toString(), Charsets.UTF_8)
        val original = legacy.readBytes()
        val queued = JsonObject(record(2000) + mapOf("state" to JsonPrimitive("queued")))
        try {
            val store = OperationTaskStore(legacy)
            assertTrue(store.claim(queued).created)
            assertTrue(store.updateActive("id-2000",
                mapOf("summary" to JsonPrimitive("progress received"))))
            val restarted = OperationTaskStore(legacy)
            val saved = restarted.get("id-2000")
            assertEquals("outcome_unknown", saved?.get("state")?.jsonPrimitive?.content)
            assertEquals("progress received", saved?.get("summary")?.jsonPrimitive?.content)
            assertFalse(restarted.claim(queued).created)
            assertArrayEquals(original, legacy.readBytes())
            val db = File(legacy.path + ".sqlite")
            assertTrue(db.exists())
            assertTrue(db.delete())
            try {
                OperationTaskStore(legacy).claim(queued)
                fail("missing activated SQLite must never replay a request")
            } catch (_: OperationTaskStorageException) {
                // Explicit fail-closed proof.
            }
            assertArrayEquals(original, legacy.readBytes())
        } finally {
            legacy.delete()
            File(legacy.path + ".sqlite").delete()
            File(legacy.path + ".sqlite-journal").delete()
            File(legacy.path + ".sqlite-wal").delete()
            File(legacy.path + ".sqlite-shm").delete()
            File(legacy.path + ".sqlite.ready").delete()
            File(legacy.path + ".lock").delete()
        }
    }
}
