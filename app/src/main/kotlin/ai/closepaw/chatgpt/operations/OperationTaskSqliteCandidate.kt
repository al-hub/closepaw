package ai.closepaw.chatgpt.operations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.*

/**
 * Android production ledger. No custom generation/manifest protocol.
 * Caller must arrange external execution only after a durable claim.
 */
internal class OperationTaskSqliteCandidate(private val dbFile: File) {
    private fun <T> database(block: (SQLiteDatabase) -> T): T {
        dbFile.parentFile?.let { require(it.isDirectory || it.mkdirs()) }
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            if (File(dbFile.path + ".ready").exists()) require(db.version == 1) { "activated_sqlite_schema_invalid" }
            require(db.version in 0..1) { "sqlite_schema_version_invalid" }
            if (db.version == 0) {
                db.execSQL(
                "CREATE TABLE IF NOT EXISTS tasks (" +
                    "task_id TEXT NOT NULL PRIMARY KEY, " +
                    "request_id TEXT UNIQUE, " +
                    "state TEXT, " +
                    "operation_id TEXT, " +
                    "created_at_ms INTEGER, " +
                    "ordinal INTEGER NOT NULL UNIQUE, " +
                    "payload_json TEXT NOT NULL)"
            )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_operation_created ON tasks(operation_id,created_at_ms)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_state_ordinal ON tasks(state,ordinal)")
            } else verifySchema(db)
            return block(db)
        } finally {
            db.close()
        }
    }

    private fun requireMigrated(db: SQLiteDatabase) {
        require(db.version == 1) { "migration_not_committed" }
    }

    companion object {
        /** Never repair/recreate a committed schema: that could forget dedup. */
        fun verifySchema(db: SQLiteDatabase) {
            db.query("tasks", arrayOf("task_id", "request_id", "state", "operation_id",
                "created_at_ms", "ordinal", "payload_json"), null, null, null, null, null, "0").use { }
            val uniqueColumns = HashSet<List<String>>()
            db.rawQuery("PRAGMA index_list('tasks')", null).use { indexes ->
                while (indexes.moveToNext()) if (indexes.getInt(indexes.getColumnIndexOrThrow("unique")) == 1 &&
                    indexes.getInt(indexes.getColumnIndexOrThrow("partial")) == 0) {
                    val index = indexes.getString(indexes.getColumnIndexOrThrow("name"))
                    val columns = ArrayList<String>()
                    // Metadata is local DB input, still quote it as SQL text.
                    db.rawQuery("PRAGMA index_info('" + index.replace("'", "''") + "')", null).use { info ->
                        while (info.moveToNext()) columns.add(info.getString(info.getColumnIndexOrThrow("name")))
                    }
                    uniqueColumns.add(columns)
                }
            }
            require(listOf("task_id", "request_id", "ordinal").all { listOf(it) in uniqueColumns }) {
                "sqlite_identity_index_missing"
            }
        }
    }

    private fun field(record: JsonObject, name: String): String =
        (record[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("missing_or_invalid_$name")

    private fun insert(db: SQLiteDatabase, record: JsonObject, ordinal: Long) {
        val data = ContentValues().apply {
            put("task_id", field(record, "task_id"))
            put("request_id", (record["request_id"] as? JsonPrimitive)?.contentOrNull)
            put("state", (record["state"] as? JsonPrimitive)?.contentOrNull)
            put("operation_id", (record["operation_id"] as? JsonPrimitive)?.contentOrNull)
            put("created_at_ms", (record["created_at_ms"] as? JsonPrimitive)?.longOrNull)
            put("ordinal", ordinal)
            put("payload_json", record.toString())
        }
        require(db.insertOrThrow("tasks", null, data) >= 0) { "task_insert_failed" }
    }

    /**
     * Imports only into a genuinely empty database in a single transaction.
     * Never deletes or modifies the original ledger.
     * An already populated DB is refused, not overwritten.
     */
    fun importOnce(records: List<JsonObject>) = importStream { accept -> records.forEach(accept) }

    private fun importStream(source: ((JsonObject) -> Unit) -> Unit) = database { db ->
        db.beginTransaction()
        try {
            require(db.version == 0) { "migration_already_committed" }
            db.rawQuery("SELECT COUNT(*) FROM tasks", null).use {
                require(it.moveToFirst() && it.getLong(0) == 0L) { "migration_database_not_empty" }
            }
            var count = 0L
            source { record ->
                insert(db, record, count++)
                // Verify through the durable row and both indexed identities,
                // without retaining an additional copy of the entire ledger.
                db.query("tasks", arrayOf("payload_json", "request_id", "ordinal"), "task_id=?",
                    arrayOf(field(record, "task_id")), null, null, null).use { row ->
                    require(row.moveToFirst() &&
                        Json.parseToJsonElement(row.getString(0)).jsonObject == record &&
                        row.getString(1) == (record["request_id"] as? JsonPrimitive)?.contentOrNull &&
                        row.getLong(2) == count - 1) { "migration_content_mismatch" }
                }
            }
            db.rawQuery("SELECT COUNT(*) FROM tasks", null).use { rows ->
                require(rows.moveToFirst() && rows.getLong(0) == count) { "migration_record_loss" }
            }
            // The completion marker must commit atomically with the imported rows.
            // A failed import leaves user_version=0 and no accepted claims.
            db.version = 1
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** First activation reads bounded records; an existing activation never reimports. */
    fun ensureMigrated(legacy: File) {
        if (File(dbFile.path + ".ready").exists()) {
            ensureMigrated(emptyList(), legacy)
            return
        }
        require(!File(legacy.path + ".history").exists()) { "legacy_overlay_requires_explicit_migration" }
        val original = originalDigest(legacy)
        val alreadyCommitted = if (dbFile.isFile) database { it.version == 1 } else false
        if (!alreadyCommitted) importStream { accept ->
            if (legacy.exists()) {
                require(OperationTaskLegacyStream.read(legacy, onRecord = accept).sha256 == original) { "legacy_source_changed" }
            }
        }
        // Also covers a process death after SQLite commit, before .ready.
        // Compare one record at a time, including order and complete JSON.
        database { db ->
            requireMigrated(db)
            db.query("tasks", arrayOf("payload_json", "ordinal"), null, null, null, null, "ordinal ASC").use { rows ->
                var index = 0L
                if (legacy.exists()) {
                    val verified = OperationTaskLegacyStream.read(legacy) { record ->
                        require(rows.moveToNext() && rows.getLong(1) == index++ &&
                            Json.parseToJsonElement(rows.getString(0)).jsonObject == record) {
                            "sqlite_migration_content_mismatch"
                        }
                    }
                    require(verified.sha256 == original) { "legacy_source_changed" }
                }
                require(!rows.moveToNext()) { "sqlite_migration_content_mismatch" }
            }
        }
        require(originalDigest(legacy) == original) { "legacy_source_changed" }
        publishMarker("closepaw-sqlite-v1 $original")
    }


    /**
     * Store activation has a separate durable sentinel. A missing DB after
     * activation must never silently trigger legacy reimport and lost dedup.
     * Call with the original ledger file locked by OperationTaskStore.
     */
    fun ensureMigrated(records: List<JsonObject>, legacy: File) {
        val sentinel = File(dbFile.path + ".ready")
        val marker = "closepaw-sqlite-v1 " + originalDigest(legacy)
        if (sentinel.exists()) {
            require(sentinel.isFile && sentinel.length() <= 128 && sentinel.readText(Charsets.UTF_8).trim() == marker) {
                "sqlite_activation_marker_invalid"
            }
            require(dbFile.isFile) { "committed_sqlite_missing" }
            database { db -> requireMigrated(db) }
            return
        }
        val alreadyCommitted = if (dbFile.isFile) database { db -> db.version == 1 } else false
        if (!alreadyCommitted) importOnce(records)
        // On a crash after DB commit but before sentinel, verify the exact
        // migration snapshot. Do NOT accept a divergent DB.
        require(allRecords() == records) { "sqlite_migration_content_mismatch" }
        publishMarker(marker)
    }

    private fun publishMarker(marker: String) {
        val sentinel = File(dbFile.path + ".ready")
        val parent = sentinel.parentFile ?: throw IllegalStateException("sqlite_parent_missing")
        val tmp = File.createTempFile("sqlite-ready-", ".tmp", parent)
        try {
            FileOutputStream(tmp).use { stream ->
                stream.write((marker + "\n").toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(tmp.toPath(), sentinel.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } finally {
            tmp.delete()
        }
    }

    private fun originalDigest(file: File): String {
        if (file.exists() && file.length() > OperationTaskDiagnostics.MAX_BYTES) throw OperationTaskDiagnosticByteException()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        if (!file.exists()) md.update("absent".toByteArray(Charsets.UTF_8))
        else file.inputStream().use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                val length = input.read(bytes)
                if (length < 0) break
                md.update(bytes, 0, length)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Persist startup uncertainty without replaying any external execution. */
    fun reconcileInterrupted() = database { db ->
        requireMigrated(db)
        db.beginTransaction()
        try {
            // Query a single row each time: updating a cursor's backing table
            // while refilling its CursorWindow can otherwise skip records.
            var lastOrdinal = -1L
            while (true) {
                val nextRow = db.query("tasks", arrayOf("task_id", "payload_json", "ordinal"),
                    "ordinal > ? AND state IN ('queued','running','cancel_requested')", arrayOf(lastOrdinal.toString()),
                    null, null, "ordinal ASC", "1")
                    .use { cursor -> if (cursor.moveToFirst()) {
                        lastOrdinal = cursor.getLong(2)
                        cursor.getString(0) to Json.parseToJsonElement(cursor.getString(1)).jsonObject
                    } else null } ?: break
                val (id, old) = nextRow
                val next = JsonObject(old + mapOf(
                    "state" to JsonPrimitive("outcome_unknown"),
                    "summary" to (old["summary"] ?: JsonPrimitive(
                        "ClosePaw restarted; underlying execution outcome is not confirmed. Do not automatically retry."))
                ))
                val values = ContentValues().apply {
                    put("state", "outcome_unknown")
                    put("payload_json", next.toString())
                }
                require(db.update("tasks", values, "task_id=?", arrayOf(id)) == 1)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Existing-task replacement and new insertion keep task/request identities unique. */
    fun put(task: JsonObject) = database { db ->
        requireMigrated(db)
        db.beginTransaction()
        try {
            val id = field(task, "task_id")
            val existing = db.query("tasks", arrayOf("request_id"), "task_id=?",
                arrayOf(id), null, null, null).use { cursor ->
                if (cursor.moveToFirst()) true to cursor.getString(0) else false to null
            }
            if (existing.first) {
                require(existing.second == (task["request_id"] as? JsonPrimitive)?.contentOrNull) {
                    "task_request_identity_immutable"
                }
                val values = ContentValues().apply {
                    put("state", (task["state"] as? JsonPrimitive)?.contentOrNull)
                    put("operation_id", (task["operation_id"] as? JsonPrimitive)?.contentOrNull)
                    put("created_at_ms", (task["created_at_ms"] as? JsonPrimitive)?.longOrNull)
                    put("payload_json", task.toString())
                }
                require(db.update("tasks", values, "task_id=?", arrayOf(id)) == 1)
            } else {
                val ordinal = db.rawQuery("SELECT COALESCE(MAX(ordinal), -1) + 1 FROM tasks", null).use {
                    require(it.moveToFirst())
                    it.getLong(0)
                }
                insert(db, task, ordinal)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Claim and request dedup are serialized by the SQLite write transaction. */
    fun claim(task: JsonObject): OperationTaskClaim = database { db ->
        requireMigrated(db)
        db.beginTransaction()
        try {
            val requestId = field(task, "request_id")
            db.query("tasks", arrayOf("payload_json"), "request_id=?", arrayOf(requestId),
                null, null, null).use { rows ->
                if (rows.moveToFirst()) {
                    val prior = Json.parseToJsonElement(rows.getString(0)).jsonObject
                    db.setTransactionSuccessful()
                    return@database OperationTaskClaim(
                        prior, created = false, conflict = prior["fingerprint"] != task["fingerprint"])
                }
            }
            val next = db.rawQuery("SELECT COALESCE(MAX(ordinal), -1)+1 FROM tasks", null).use {
                require(it.moveToFirst())
                it.getLong(0)
            }
            insert(db, task, next)
            db.setTransactionSuccessful()
            OperationTaskClaim(task, created = true)
        } finally {
            db.endTransaction()
        }
    }

    fun get(taskId: String): JsonObject? = database { db ->
        requireMigrated(db)
        db.query("tasks", arrayOf("payload_json"), "task_id=?", arrayOf(taskId),
            null, null, null).use { rows ->
            if (rows.moveToFirst()) Json.parseToJsonElement(rows.getString(0)).jsonObject else null
        }
    }
    /** Update a retained record without changing immutable identity or ordering. */
    fun update(taskId: String, changes: Map<String, JsonElement>, activeOnly: Boolean = false): Boolean =
        database { db ->
            requireMigrated(db)
            db.beginTransaction()
            try {
                val current = db.query("tasks", arrayOf("payload_json"), "task_id=?",
                    arrayOf(taskId), null, null, null).use { rows ->
                    if (rows.moveToFirst()) Json.parseToJsonElement(rows.getString(0)).jsonObject else null
                }
                if (current == null || (activeOnly && (
                        current["state"]?.jsonPrimitive?.content in
                            setOf("succeeded", "failed", "cancelled", "timed_out", "interrupted") ||
                        (current["output_validation_failed"] as? JsonPrimitive)?.booleanOrNull == true
                    ))) {
                    db.setTransactionSuccessful()
                    return@database false
                }
                val next = JsonObject(current + changes)
                require(field(next, "task_id") == field(current, "task_id") &&
                    (next["request_id"] ?: JsonNull) == (current["request_id"] ?: JsonNull)) {
                    "task_identity_immutable"
                }
                val values = ContentValues().apply {
                    put("state", (next["state"] as? JsonPrimitive)?.contentOrNull)
                    put("operation_id", (next["operation_id"] as? JsonPrimitive)?.contentOrNull)
                    put("created_at_ms", (next["created_at_ms"] as? JsonPrimitive)?.longOrNull)
                    put("payload_json", next.toString())
                }
                require(db.update("tasks", values, "task_id=?", arrayOf(taskId)) == 1)
                db.setTransactionSuccessful()
                true
            } finally {
                db.endTransaction()
            }
        }

    fun byRequest(requestId: String): JsonObject? = database { db ->
        requireMigrated(db)
        db.query("tasks", arrayOf("payload_json"), "request_id=?", arrayOf(requestId),
            null, null, null).use { rows ->
            if (rows.moveToFirst()) Json.parseToJsonElement(rows.getString(0)).jsonObject else null
        }
    }

    fun latestOutcomeUnknown(operationId: String): Boolean = database { db ->
        requireMigrated(db)
        db.query("tasks", arrayOf("state"), "operation_id=?", arrayOf(operationId),
            null, null, "COALESCE(created_at_ms, 0) DESC, ordinal ASC", "1").use { rows ->
            rows.moveToFirst() && rows.getString(0) == "outcome_unknown"
        }
    }

    /** Read all retained records in stable insertion order for compatibility checks. */
    fun allRecords(): List<JsonObject> = database { db ->
        requireMigrated(db)
        val result = ArrayList<JsonObject>()
        db.query("tasks", arrayOf("payload_json"), null, null, null, null, "ordinal ASC")
            .use { rows ->
                while (rows.moveToNext()) result.add(Json.parseToJsonElement(rows.getString(0)).jsonObject)
            }
        result
    }

    fun forEachRecord(accept: (JsonObject) -> Unit) = database { db ->
        requireMigrated(db)
        db.query("tasks", arrayOf("payload_json"), null, null, null, null, "ordinal ASC").use { rows ->
            while (rows.moveToNext()) accept(Json.parseToJsonElement(rows.getString(0)).jsonObject)
        }
    }

}
