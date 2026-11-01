package ai.closepaw.chatgpt.operations

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.*

internal data class OperationTaskClaim(val task: JsonObject, val created: Boolean, val conflict: Boolean = false)

/** Durable request ledger. Every instance/process transacts against the same current disk snapshot. */
internal class OperationTaskStore(
    file: File,
    private val beforeStage: (String) -> Unit = {},
    private val diagnostic: OperationTaskDiagnostics = OperationTaskDiagnostics(file),
) {
    private fun <T> stage(name: String, block: () -> T): T = try {
        beforeStage(name)
        block()
    } catch (error: Exception) {
        if (error is OperationTaskStorageException) {
            diagnostic.record(error.stage, error)
            throw error
        }
        diagnostic.record(name, error)
        throw OperationTaskStorageException(name, OperationTaskDiagnostics.reason(error), error)
    }
    fun diagnostics(): JsonObject = diagnostic.snapshot()
    private val file = stage("canonical_path") { file.canonicalFile }
    private val pathLock = pathLocks.computeIfAbsent(this.file.path) { Any() }
    private val history = OperationTaskRetainedOverlay(this.file)
    // Android production uses SQLite; JVM unit tests exercise legacy contracts.
    private val sqlite = if (android.os.Build.VERSION.SDK_INT > 0)
        OperationTaskSqliteCandidate(File(this.file.path + ".sqlite")) else null

    init {
        if (sqlite != null) {
            // Hold the legacy OS lock only during migration/provenance checks.
            // Once the replacement is activated, do NOT parse the entire old
            // JSON on every new store instance.
            withLedgerLock {
                stage("sqlite_migration") {
                    val migrationRecords =
                        if (File(file.path + ".sqlite.ready").exists()) emptyList()
                        else loadForMigration().values.toList()
                    sqlite.ensureMigrated(migrationRecords, file)
                }
            }
            stage("sqlite_reconcile") { sqlite.reconcileInterrupted() }
        } else {
            transaction { tasks ->
                var changed = false
                tasks.entries.forEach { entry ->
                    if (entry.value["state"]?.jsonPrimitive?.content in setOf("queued", "running", "cancel_requested")) {
                        entry.setValue(JsonObject(entry.value + mapOf(
                            "state" to JsonPrimitive("outcome_unknown"),
                            "summary" to (entry.value["summary"] ?: JsonPrimitive("ClosePaw restarted; underlying execution outcome is not confirmed. Do not automatically retry.")),
                        )))
                        changed = true
                    }
                }
                if (changed) persist(tasks)
            }
        }
    }

    fun get(id: String): JsonObject? =
        if (sqlite != null) stage("sqlite_get") { sqlite.get(id) }
        else transaction { it[id] }
    /** Reclaim an identity only after all queryable terminal output and dedup are durable here. */
    fun retainedShellLinks(): Map<String, String> {
        val records = if (sqlite != null) stage("sqlite_shell_links") { sqlite.allRecords() }
            else transaction { it.values.toList() }
        return records.filter(::hasRetainedShellResult).associate {
            it.getValue("task_id").jsonPrimitive.content to
                it.getValue("result").jsonObject.getValue("data").jsonObject.getValue("job_id").jsonPrimitive.content
        }
    }
    private fun hasRetainedShellResult(task: JsonObject): Boolean {
        val result = task["result"] as? JsonObject ?: return false
        val data = result["data"] as? JsonObject ?: return false
        return task["operation_id"]?.jsonPrimitive?.content == "linux.shell.exec" &&
            task["state"]?.jsonPrimitive?.content in TERMINAL_STATES &&
            task["output_validation_failed"]?.jsonPrimitive?.booleanOrNull != true &&
            result["status"]?.jsonPrimitive?.content in setOf("succeeded", "failed") &&
            (result["stdout"] as? JsonPrimitive)?.isString == true &&
            (result["stderr"] as? JsonPrimitive)?.isString == true &&
            (data["job_id"] as? JsonPrimitive)?.isString == true &&
            data["state"] == task["state"] &&
            data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull == false &&
            (data["state"]?.jsonPrimitive?.content != "cancelled" ||
                data["cancel_confirmed"]?.jsonPrimitive?.booleanOrNull == true)
    }
    /** A later confirmed question supersedes older ambiguous submissions; never replay the latest unknown send through another adapter. */
    fun latestOutcomeUnknown(operationId: String): Boolean =
        if (sqlite != null) stage("sqlite_latest_unknown") { sqlite.latestOutcomeUnknown(operationId) }
        else transaction { tasks ->
            tasks.values.filter { it["operation_id"]?.jsonPrimitive?.content == operationId }
                .maxByOrNull { it["created_at_ms"]?.jsonPrimitive?.longOrNull ?: 0L }
                ?.get("state")?.jsonPrimitive?.content == "outcome_unknown"
        }
    fun byRequest(id: String): JsonObject? =
        if (sqlite != null) stage("sqlite_by_request") { sqlite.byRequest(id) }
        else transaction { tasks -> findRequest(tasks, id) }

    /** Atomic dedup and insertion, including competing dispatchers using different store instances. */
    fun claim(task: JsonObject): OperationTaskClaim {
        if (sqlite != null) return stage("sqlite_claim") { sqlite.claim(task) }
        return transaction { tasks ->
        val request = task.getValue("request_id").jsonPrimitive.content
        val fingerprint = task.getValue("fingerprint")
        val existing = findRequest(tasks, request)
        if (existing != null) OperationTaskClaim(existing, created = false, conflict = existing["fingerprint"] != fingerprint)
        else {
            insert(tasks, task)
            persist(tasks)
            OperationTaskClaim(task, created = true)
        }
        }
    }

    fun put(task: JsonObject) {
        if (sqlite != null) {
            stage("sqlite_put") { sqlite.put(task) }
            return
        }
        transaction { tasks -> insert(tasks, task); persist(tasks) }
    }
    fun update(id: String, values: Map<String, JsonElement>) {
        if (sqlite != null) {
            stage("sqlite_update") { sqlite.update(id, values) }
            return
        }
        transaction { tasks ->
            val previous = tasks[id]
            if (previous != null) {
                insert(tasks, JsonObject(previous + values))
                persist(tasks)
            }
        }
    }
    /** Atomic terminal guard shared by execution completion and cancellation. */
    fun updateActive(id: String, values: Map<String, JsonElement>): Boolean {
        if (sqlite != null) return stage("sqlite_update_active") {
            sqlite.update(id, values, activeOnly = true)
        }
        return transaction { tasks ->
            val previous = tasks[id]
            if (previous == null || previous["state"]?.jsonPrimitive?.content in TERMINAL_STATES ||
                (previous["output_validation_failed"] as? JsonPrimitive)?.booleanOrNull == true) false
            else {
                insert(tasks, JsonObject(previous + values))
                persist(tasks)
                true
            }
        }
    }
    private fun findRequest(tasks: Map<String, JsonObject>, id: String): JsonObject? =
        tasks.values.firstOrNull { it["request_id"]?.jsonPrimitive?.content == id }

    private fun insert(tasks: MutableMap<String, JsonObject>, task: JsonObject, legacyValidation: Boolean = false) {
        val id = task.getValue("task_id").jsonPrimitive.content
        stage("capacity_check") {
            require(!legacyValidation || tasks.size < MAX_TASKS || id in tasks) { "Task ledger full; archive explicitly before accepting new requests" }
        }
        val request = (task["request_id"] as? JsonPrimitive)?.content
        require(request == null || tasks.values.none { it["request_id"]?.jsonPrimitive?.content == request && it["task_id"]?.jsonPrimitive?.content != id }) { "Request id already belongs to another task" }
        tasks[id] = task
    }
    private fun load(): LinkedHashMap<String, JsonObject> {
        val tasks = readLegacyRecords()
        stage("overlay_read") { history.mergeInto(tasks) }
        return tasks
    }
    private fun loadForMigration(): LinkedHashMap<String, JsonObject> {
        // Historical count is not an admission quota: importing 2001+ existing
        // records must not fail merely because the old aggregate cap is hit.
        val tasks = readLegacyRecords(enforceLegacyLimit = false)
        stage("overlay_read") { history.mergeInto(tasks) }
        stage("migration_preflight") { OperationTaskHistoryPreflight.inspect(tasks.values) }
        return tasks
    }
    private fun readLegacyRecords(): LinkedHashMap<String, JsonObject> =
        readLegacyRecords(enforceLegacyLimit = true)
    private fun readLegacyRecords(enforceLegacyLimit: Boolean): LinkedHashMap<String, JsonObject> {
        val tasks = linkedMapOf<String, JsonObject>()
        if (!file.exists()) return tasks
        // Corruption fails closed, including for a store opened before the file became corrupt.
        val raw = stage("read") { file.readText() }
        val records = stage("decode") { Json.parseToJsonElement(raw).jsonArray }
        stage("validate_records") { records.forEach { item ->
            val task = item.jsonObject
            val id = task.getValue("task_id").jsonPrimitive.content
            require(id !in tasks) { "Duplicate task id in ledger" }
            insert(tasks, task, legacyValidation = enforceLegacyLimit)
        } }
        return tasks
    }
    private fun <T> transaction(block: (LinkedHashMap<String, JsonObject>) -> T): T =
        withLedgerLock { block(load()) }

    private fun <T> withLedgerLock(block: () -> T): T = synchronized(pathLock) {
        stage("directory") {
            file.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Ledger directory unavailable" } }
        }
        // JVM locks prevent OverlappingFileLockException; the OS lock also serializes separate app processes.
        stage("open_lock") { RandomAccessFile(File(file.path + ".lock"), "rw") }.use { lockFile ->
            val lock = stage("acquire_lock") {
                lockFile.channel.tryLock() ?: throw OperationTaskStorageException(
                    "acquire_lock", "lock_busy", IllegalStateException("Ledger is locked"))
            }
            try { block() } finally { lock.release() }
        }
    }
    private fun persist(tasks: Map<String, JsonObject>) {
        if (history.isActive() || tasks.size > MAX_TASKS) {
            stage("overlay_write") { history.persistChanges(tasks, ::readLegacyRecords) }
            return
        }
        // Separate staging paths prevent collision even if future callers bypass process-local coordination.
        val temporary = stage("stage_create") { File.createTempFile("task-ledger-", ".tmp", file.parentFile) }
        try {
            stage("stage_open") { FileOutputStream(temporary) }.use { stream ->
                stage("write") { stream.write(JsonArray(tasks.values.toList()).toString().toByteArray(Charsets.UTF_8)) }
                stage("fsync") { stream.fd.sync() }
            }
            stage("atomic_replace") {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }
    companion object {
        const val MAX_TASKS = 2000
        private val pathLocks = ConcurrentHashMap<String, Any>()
        private val TERMINAL_STATES = setOf("succeeded", "failed", "cancelled", "timed_out", "interrupted")
    }
}
