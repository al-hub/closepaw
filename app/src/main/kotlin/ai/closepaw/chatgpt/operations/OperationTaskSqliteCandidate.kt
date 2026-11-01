package ai.closepaw.chatgpt.operations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
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
                    "request_id TEXT NOT NULL UNIQUE, " +
                    "state TEXT NOT NULL, " +
                    "operation_id TEXT NOT NULL, " +
                    "created_at_ms INTEGER NOT NULL, " +
                    "ordinal INTEGER NOT NULL UNIQUE, " +
                    "payload_json TEXT NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_operation_created ON tasks(operation_id,created_at_ms)")
            return block(db)
        } finally {
            db.close()
        }
    }

    private fun field(record: JsonObject, name: String): String =
        (record[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("missing_or_invalid_$name")

    private fun insert(db: SQLiteDatabase, record: JsonObject, ordinal: Long) {
        val data = ContentValues().apply {
            put("task_id", field(record, "task_id"))
            put("request_id", field(record, "request_id"))
            put("state", field(record, "state"))
            put("operation_id", field(record, "operation_id"))
            put("created_at_ms", record["created_at_ms"]?.jsonPrimitive?.longOrNull
                ?: throw IllegalArgumentException("missing_created_at_ms"))
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

    /** Claim and request dedup are serialized by the SQLite write transaction. */
    fun claim(task: JsonObject): OperationTaskClaim = database { db ->
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
}
