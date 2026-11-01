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
 * Not wired into production admission until full migration and Android
 * instrumentation gates pass. No custom generation/manifest protocol.
 * Caller must arrange external execution only after a durable claim.
 */
internal class OperationTaskSqliteCandidate(private val dbFile: File) {
    private fun <T> database(block: (SQLiteDatabase) -> T): T {
        dbFile.parentFile?.let { require(it.isDirectory || it.mkdirs()) }
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
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
            return block(db)
        } finally {
            db.close()
        }
    }

    private fun requireMigrated(db: SQLiteDatabase) {
        require(db.version == 1) { "migration_not_committed" }
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
    fun importOnce(records: List<JsonObject>) = database { db ->
        db.beginTransaction()
        try {
            db.rawQuery("SELECT COUNT(*) FROM tasks", null).use {
                require(it.moveToFirst() && it.getLong(0) == 0L) { "migration_database_not_empty" }
            }
            records.forEachIndexed { i, record -> insert(db, record, i.toLong()) }
            db.rawQuery("SELECT COUNT(*) FROM tasks", null).use {
                require(it.moveToFirst() && it.getLong(0) == records.size.toLong()) { "migration_count_mismatch" }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
            require(sentinel.isFile && sentinel.readText(Charsets.UTF_8).trim() == marker) {
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
            val interrupted = ArrayList<Pair<String, JsonObject>>()
            db.query("tasks", arrayOf("task_id", "payload_json"),
                "state IN ('queued','running','cancel_requested')", null, null, null, null)
                .use { cursor ->
                    while (cursor.moveToNext()) interrupted.add(
                        cursor.getString(0) to Json.parseToJsonElement(cursor.getString(1)).jsonObject)
                }
            interrupted.forEach { (id, old) ->
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
        db.query("tasks", arrayOf("payload_json"), "task_id=?", arrayOf(taskId),
            null, null, null).use { rows ->
            if (rows.moveToFirst()) Json.parseToJsonElement(rows.getString(0)).jsonObject else null
        }
    }
    /** Update a retained record without changing immutable identity or ordering. */
    fun update(taskId: String, changes: Map<String, JsonElement>, activeOnly: Boolean = false): Boolean =
        database { db ->
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
                    field(next, "request_id") == field(current, "request_id")) { "task_identity_immutable" }
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
        db.query("tasks", arrayOf("payload_json"), "request_id=?", arrayOf(requestId),
            null, null, null).use { rows ->
            if (rows.moveToFirst()) Json.parseToJsonElement(rows.getString(0)).jsonObject else null
        }
    }

    fun latestOutcomeUnknown(operationId: String): Boolean = database { db ->
        db.query("tasks", arrayOf("state"), "operation_id=?", arrayOf(operationId),
            null, null, "created_at_ms DESC, ordinal DESC", "1").use { rows ->
            rows.moveToFirst() && rows.getString(0) == "outcome_unknown"
        }
    }

    /** Read all retained records in stable insertion order for compatibility checks. */
    fun allRecords(): List<JsonObject> = database { db ->
        val result = ArrayList<JsonObject>()
        db.query("tasks", arrayOf("payload_json"), null, null, null, null, "ordinal ASC")
            .use { rows ->
                while (rows.moveToNext()) result.add(Json.parseToJsonElement(rows.getString(0)).jsonObject)
            }
        result
    }

}
