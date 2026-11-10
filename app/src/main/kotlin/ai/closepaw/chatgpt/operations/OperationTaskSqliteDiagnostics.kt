package ai.closepaw.chatgpt.operations

import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlinx.serialization.json.*

/** Does not construct a store, create SQLite, take a write lock or reconcile tasks. */
internal class OperationTaskSqliteDiagnostics(private val legacy: File) {
    fun snapshot(): JsonObject = buildJsonObject {
        put("diagnostic_version", 2)
        put("backend", "sqlite")
        put("snapshot_only", true)
        put("write_test_performed", false)
        put("write_health", "not_tested")
        put("recovery_attempted", false)
        put("pre_upgrade_integrity", "not_available")
        put("file_exists", legacy.exists())
        if (legacy.exists()) put("file_bytes", legacy.length())
        try {
            val database = File(legacy.path + ".sqlite")
            val marker = File(database.path + ".ready")
            require(marker.isFile && marker.length() <= 128 && database.isFile) { "sqlite_activation_unavailable" }
            val expected = marker.readText(Charsets.UTF_8).trim()
            require(Regex("closepaw-sqlite-v1 [a-f0-9]{64}").matches(expected)) { "sqlite_activation_invalid" }
            var tasks = 0; var active = 0; var terminal = 0; var unknown = 0
            var exact = 0; var reconciled = 0; var mismatched = 0
            var originalCount = 0
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                require(db.version == 1) { "sqlite_migration_not_committed" }
                OperationTaskSqliteCandidate.verifySchema(db)
                db.rawQuery("PRAGMA quick_check(1)", null).use { row ->
                    require(row.moveToFirst() && row.getString(0) == "ok") { "sqlite_integrity_failed" }
                }
                db.query("tasks", arrayOf("task_id", "request_id", "state", "ordinal", "payload_json", "operation_id", "created_at_ms"),
                    null, null, null, null, "ordinal ASC").use { rows ->
                    fun nextRecord(): JsonObject {
                        require(rows.moveToNext()) { "sqlite_history_missing" }
                        val record = Json.parseToJsonElement(rows.getString(4)).jsonObject
                        require(rows.getLong(3) == tasks.toLong() &&
                            (record["task_id"] as? JsonPrimitive)?.contentOrNull == rows.getString(0) &&
                            (record["request_id"] as? JsonPrimitive)?.contentOrNull == rows.getString(1) &&
                            (record["state"] as? JsonPrimitive)?.contentOrNull == rows.getString(2) &&
                            (record["operation_id"] as? JsonPrimitive)?.contentOrNull == rows.getString(5) &&
                            (record["created_at_ms"] as? JsonPrimitive)?.longOrNull ==
                                (if (rows.isNull(6)) null else rows.getLong(6))) { "sqlite_index_mismatch" }
                        tasks++
                        when ((record["state"] as? JsonPrimitive)?.contentOrNull) {
                            "queued", "running", "cancel_requested" -> active++
                            "succeeded", "failed", "cancelled", "timed_out", "interrupted" -> terminal++
                            else -> unknown++
                        }
                        return record
                    }
                    val digest = if (legacy.exists()) {
                        val source = OperationTaskLegacyStream.read(legacy) { original ->
                            originalCount++
                            val current = nextRecord()
                            val oldState = (original["state"] as? JsonPrimitive)?.contentOrNull
                            val restart = JsonObject(original + mapOf(
                                "state" to JsonPrimitive("outcome_unknown"),
                                "summary" to (original["summary"] ?: JsonPrimitive(
                                    "ClosePaw restarted; underlying execution outcome is not confirmed. Do not automatically retry."))
                            ))
                            when {
                                current == original -> exact++
                                oldState in setOf("queued", "running", "cancel_requested") && current == restart -> reconciled++
                                else -> mismatched++
                            }
                        }
                        source.sha256
                    } else OperationTaskLegacyStream.absentDigest()
                    require(expected == "closepaw-sqlite-v1 $digest") { "legacy_digest_mismatch" }
                    // Continue without whole-history allocation; no payload or
                    // identity is returned through the diagnostic interface.
                    while (!rows.isLast && rows.count > 0) nextRecord()
                    put("legacy_sha256", digest)
                }
            }
            // Report complete counters only after all validation succeeds.
            put("task_count", tasks); put("active_count", active)
            put("terminal_count", terminal); put("unknown_count", unknown)
            put("legacy_task_count", originalCount); put("legacy_exact_count", exact)
            put("legacy_reconciled_count", reconciled); put("legacy_mismatch_count", mismatched)
            put("legacy_integrity", if (mismatched == 0) "verified_current" else "not_proven")
            put("read_state", "readable")
            put("reason_code", if (mismatched == 0) "sqlite_snapshot_verified" else "legacy_content_changed")
        } catch (error: Exception) {
            put("read_state", "failed")
            put("reason_code", "sqlite_verification_failed")
            put("exception_category", OperationTaskDiagnostics.category(error))
        }
    }
}
