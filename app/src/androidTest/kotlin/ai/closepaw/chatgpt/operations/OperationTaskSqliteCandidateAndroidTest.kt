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
    private fun isolated(block: (File) -> Unit) {
        val dir = File.createTempFile("task-stream-219-", ".dir",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        check(dir.delete() && dir.mkdirs())
        try { block(File(dir, "tasks.json")) } finally { dir.deleteRecursively() }
    }

    @Test fun measuredSizeStreamingMigrationAndReadOnlySqliteAuditPreserveEveryRecord() = isolated { legacy ->
        legacy.bufferedWriter().use { out ->
            out.write("[")
            repeat(2001) { i ->
                if (i > 0) out.write(",")
                out.write(JsonObject(record(i) + mapOf("payload" to JsonPrimitive("x".repeat(6200)))).toString())
            }
            out.write("]")
        }
        assertTrue(legacy.length() > 12_000_000)
        val original = OperationTaskLegacyStream.read(legacy)
        val store = OperationTaskStore(legacy)
        var visited = 0
        OperationTaskSqliteCandidate(File(legacy.path + ".sqlite")).forEachRecord { task ->
            val i = visited++
            assertEquals(JsonObject(record(i) + mapOf("payload" to JsonPrimitive("x".repeat(6200)))),
                task)
        }
        assertEquals(2001, visited)
        assertEquals("id-2000", store.byRequest("request-2000")?.get("task_id")?.jsonPrimitive?.content)
        assertTrue(store.claim(record(2001)).created)
        val diag = OperationTaskDiagnostics(legacy).snapshot()
        assertEquals("sqlite", diag["backend"]?.jsonPrimitive?.content)
        assertEquals("readable", diag["read_state"]?.jsonPrimitive?.content)
        assertEquals("verified_current", diag["legacy_integrity"]?.jsonPrimitive?.content)
        assertEquals(2001, diag["legacy_exact_count"]?.jsonPrimitive?.int)
        assertEquals(2002, diag["task_count"]?.jsonPrimitive?.int)
        assertEquals(original.sha256, diag["legacy_sha256"]?.jsonPrimitive?.content)
        assertEquals("not_tested", diag["write_health"]?.jsonPrimitive?.content)
        assertFalse(diag.toString().contains("future_field"))
        assertEquals(original.sha256, OperationTaskLegacyStream.read(legacy).sha256)
        assertFalse(OperationTaskStore(legacy).claim(record(2001)).created)
    }

    @Test fun passiveAuditDoesNotReconcileWriteCreateOrHideChangedHistory() = isolated { legacy ->
        legacy.writeText(JsonArray(listOf(record(0))).toString())
        val store = OperationTaskStore(legacy)
        val dbFile = File(legacy.path + ".sqlite")
        val before = dbFile.readBytes()
        val source = legacy.readBytes()
        assertEquals("verified_current", OperationTaskDiagnostics(legacy).snapshot()["legacy_integrity"]?.jsonPrimitive?.content)
        assertArrayEquals(before, dbFile.readBytes())
        store.update("id-0", mapOf("result" to buildJsonObject { put("stdout", "changed") }))
        val diag = OperationTaskDiagnostics(legacy).snapshot()
        assertEquals("not_proven", diag["legacy_integrity"]?.jsonPrimitive?.content)
        assertEquals(1, diag["legacy_mismatch_count"]?.jsonPrimitive?.int)
        assertArrayEquals(source, legacy.readBytes())
        assertTrue(dbFile.delete())
        assertEquals("failed", OperationTaskDiagnostics(legacy).snapshot()["read_state"]?.jsonPrimitive?.content)
        assertFalse("Passive diagnosis must not manufacture a missing DB", dbFile.exists())
    }

    @Test fun invalidStreamingImportRollsBackAndDoesNotPublishActivation() = isolated { legacy ->
        legacy.writeText("[" + record(0) + "," + record(0) + "]")
        val original = legacy.readBytes()
        try { OperationTaskStore(legacy); fail("duplicate imported") }
        catch (_: OperationTaskStorageException) { }
        assertFalse(File(legacy.path + ".sqlite.ready").exists())
        android.database.sqlite.SQLiteDatabase.openDatabase(legacy.path + ".sqlite", null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(0, db.version)
            db.rawQuery("SELECT COUNT(*) FROM tasks", null).use { rows ->
                assertTrue(rows.moveToFirst()); assertEquals(0, rows.getInt(0))
            }
        }
        assertArrayEquals(original, legacy.readBytes())
    }

    @Test fun competingConnectionsClaimOnlyOnceAndRestartKeepsUnknownWork() = isolated { legacy ->
        val first = OperationTaskStore(legacy)
        val second = OperationTaskStore(legacy)
        val queued = JsonObject(record(0) + mapOf("state" to JsonPrimitive("queued")))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val claims = (0 until 16).map { i -> pool.submit<OperationTaskClaim> {
                (if (i % 2 == 0) first else second).claim(queued)
            } }.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, claims.count { it.created })
            assertTrue(claims.all { it.task["task_id"] == JsonPrimitive("id-0") })
        } finally { pool.shutdownNow() }
        val restarted = OperationTaskStore(legacy)
        assertFalse(restarted.claim(queued).created)
        assertEquals("outcome_unknown", restarted.get("id-0")?.get("state")?.jsonPrimitive?.content)
        assertEquals(1, OperationTaskDiagnostics(legacy).snapshot()["unknown_count"]?.jsonPrimitive?.int)
    }

    @Test fun restartAuditAllowsOnlyExactUncertaintyReconciliation() = isolated { legacy ->
        val running = JsonObject(record(0) + mapOf("state" to JsonPrimitive("running")))
        legacy.writeText(JsonArray(listOf(running)).toString())
        OperationTaskStore(legacy)
        val diag = OperationTaskDiagnostics(legacy).snapshot()
        assertEquals("verified_current", diag["legacy_integrity"]?.jsonPrimitive?.content)
        assertEquals(1, diag["legacy_reconciled_count"]?.jsonPrimitive?.int)
        assertEquals(0, diag["legacy_mismatch_count"]?.jsonPrimitive?.int)
    }

    @Test fun missingCommittedSchemaNeverManufacturesAnEmptyDedupStore() = isolated { legacy ->
        legacy.writeText(JsonArray(listOf(record(0))).toString())
        OperationTaskStore(legacy)
        val dbFile = File(legacy.path + ".sqlite")
        android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.path, null, 0).use { db ->
            db.execSQL("DROP TABLE tasks")
            assertEquals(1, db.version)
        }
        try { OperationTaskStore(legacy).claim(record(0)); fail("lost schema accepted") }
        catch (_: OperationTaskStorageException) { }
        assertEquals("failed", OperationTaskDiagnostics(legacy).snapshot()["read_state"]?.jsonPrimitive?.content)
        android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='tasks'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test fun passiveAuditRejectsDivergentSafetyIndexes() = isolated { legacy ->
        legacy.writeText(JsonArray(listOf(record(0))).toString())
        OperationTaskStore(legacy)
        android.database.sqlite.SQLiteDatabase.openDatabase(legacy.path + ".sqlite", null, 0).use { db ->
            db.execSQL("UPDATE tasks SET operation_id='other', created_at_ms=999 WHERE task_id='id-0'")
        }
        val diag = OperationTaskDiagnostics(legacy).snapshot()
        assertEquals("failed", diag["read_state"]?.jsonPrimitive?.content)
        assertFalse(diag.containsKey("legacy_integrity"))
    }

    @Test fun maximumDispatcherEscapedOutputsRemainQueryableAfterStreamingImport() = isolated { legacy ->
        val output = "\u0001".repeat(OperationDispatcher.MAX_OUTPUT)
        val task = JsonObject(record(0) + mapOf("result" to buildJsonObject {
            put("stdout", output); put("stderr", output)
        }))
        legacy.writeText(JsonArray(listOf(task)).toString())
        val store = OperationTaskStore(legacy)
        assertEquals(task, store.get("id-0"))
        assertEquals("verified_current", OperationTaskDiagnostics(legacy).snapshot()["legacy_integrity"]?.jsonPrimitive?.content)
    }

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

    @Test fun migratedLegacyNullRequestAndEqualTimestampsRemainReadableAndIndexed() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("task-compat-219-", ".db", dir)
        file.delete()
        try {
            val unknown = JsonObject(record(0) + mapOf(
                "state" to JsonPrimitive("outcome_unknown"),
                "created_at_ms" to JsonPrimitive(100L)
            ))
            val sameTime = JsonObject(record(1) + mapOf("created_at_ms" to JsonPrimitive(100L)))
            val noRequest = JsonObject(record(2) - "request_id")
            val store = OperationTaskSqliteCandidate(file)
            store.importOnce(listOf(unknown, sameTime, noRequest))
            // Legacy maxByOrNull picks the first inserted task for tied timestamps.
            assertTrue(store.latestOutcomeUnknown("linux.shell.exec"))
            assertTrue(store.update("id-2", mapOf("summary" to JsonPrimitive("retained"))))
            assertEquals("retained", store.get("id-2")?.get("summary")?.jsonPrimitive?.content)
            assertNull(store.byRequest("request-2"))
            store.put(JsonObject(unknown + mapOf(
                "operation_id" to JsonPrimitive("gemini.question.ask"),
                "created_at_ms" to JsonPrimitive(200L),
                "state" to JsonPrimitive("succeeded")
            )))
            assertFalse(store.latestOutcomeUnknown("linux.shell.exec"))
            android.database.sqlite.SQLiteDatabase.openDatabase(
                file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.query("tasks", arrayOf("operation_id", "created_at_ms"),
                    "task_id=?", arrayOf("id-0"), null, null, null).use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals("gemini.question.ask", row.getString(0))
                    assertEquals(200L, row.getLong(1))
                }
            }
            val conflict = store.claim(JsonObject(record(1) + mapOf(
                "fingerprint" to JsonPrimitive("DIFFERENT")
            )))
            assertFalse(conflict.created)
            assertTrue(conflict.conflict)
            assertEquals(sameTime, store.get("id-1"))
        } finally {
            file.delete()
            File(file.path + "-journal").delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
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

    @Test fun emptyFreshInstallStoreCanClaimCompleteAndRestartWithoutLegacyData() {
        // Isolated app-cache fixture. Never delete or reset the installed user's ledger.
        val dir = File.createTempFile("task-empty-start-219-", ".dir",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        assertTrue(dir.delete())
        assertTrue(dir.mkdirs())
        val absentLegacy = File(dir, "fresh-ledger.json")
        try {
            assertFalse(absentLegacy.exists())
            val first = OperationTaskStore(absentLegacy)
            assertNull(first.byRequest("request-0"))
            val queued = JsonObject(record(0) + mapOf("state" to JsonPrimitive("queued")))
            assertTrue(first.claim(queued).created)
            assertTrue(first.updateActive("id-0", mapOf(
                "state" to JsonPrimitive("succeeded"),
                "summary" to JsonPrimitive("completed without external execution")
            )))
            val restarted = OperationTaskStore(absentLegacy)
            val saved = restarted.byRequest("request-0")
            assertEquals("succeeded", saved?.get("state")?.jsonPrimitive?.content)
            assertEquals("completed without external execution",
                saved?.get("summary")?.jsonPrimitive?.content)
            assertFalse(restarted.claim(queued).created)
            assertEquals("id-0", restarted.get("id-0")?.get("task_id")?.jsonPrimitive?.content)
            assertTrue(File(absentLegacy.path + ".sqlite.ready").exists())
            assertFalse("A clean start must not manufacture legacy JSON", absentLegacy.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
