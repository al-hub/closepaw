package ai.closepaw.chatgpt.operations

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.*

internal data class OperationTaskClaim(val task: JsonObject, val created: Boolean, val conflict: Boolean = false)

/** Durable request ledger. Every instance/process transacts against the same current disk snapshot. */
internal class OperationTaskStore(file: File) {
    private val file = file.canonicalFile
    private val pathLock = pathLocks.computeIfAbsent(this.file.path) { Any() }

    init {
        transaction { tasks ->
            var changed = false
            tasks.entries.forEach { entry ->
                if (entry.value["state"]?.jsonPrimitive?.content in setOf("queued", "running", "cancel_requested")) {
                    entry.setValue(JsonObject(entry.value + mapOf(
                        "state" to JsonPrimitive("outcome_unknown"),
                        "summary" to JsonPrimitive("ClosePaw restarted; underlying execution outcome is not confirmed. Do not automatically retry."),
                    )))
                    changed = true
                }
            }
            if (changed) persist(tasks)
        }
    }

    fun get(id: String): JsonObject? = transaction { it[id] }
    /** A later confirmed question supersedes older ambiguous submissions; never replay the latest unknown send through another adapter. */
    fun latestOutcomeUnknown(operationId: String): Boolean = transaction { tasks ->
        tasks.values.filter { it["operation_id"]?.jsonPrimitive?.content == operationId }
            .maxByOrNull { it["created_at_ms"]?.jsonPrimitive?.longOrNull ?: 0L }
            ?.get("state")?.jsonPrimitive?.content == "outcome_unknown"
    }
    fun byRequest(id: String): JsonObject? = transaction { tasks -> findRequest(tasks, id) }

    /** Atomic dedup and insertion, including competing dispatchers using different store instances. */
    fun claim(task: JsonObject): OperationTaskClaim = transaction { tasks ->
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

    fun put(task: JsonObject) = transaction { tasks ->
        insert(tasks, task)
        persist(tasks)
    }
    fun update(id: String, values: Map<String, JsonElement>) = transaction { tasks ->
        val previous = tasks[id]
        if (previous != null) {
            insert(tasks, JsonObject(previous + values))
            persist(tasks)
        }
    }
    /** Atomic terminal guard shared by execution completion and cancellation. */
    fun updateActive(id: String, values: Map<String, JsonElement>): Boolean = transaction { tasks ->
        val previous = tasks[id]
        if (previous == null || previous["state"]?.jsonPrimitive?.content in TERMINAL_STATES ||
            (previous["output_validation_failed"] as? JsonPrimitive)?.booleanOrNull == true) false
        else {
            insert(tasks, JsonObject(previous + values))
            persist(tasks)
            true
        }
    }
    private fun findRequest(tasks: Map<String, JsonObject>, id: String): JsonObject? =
        tasks.values.firstOrNull { it["request_id"]?.jsonPrimitive?.content == id }

    private fun insert(tasks: MutableMap<String, JsonObject>, task: JsonObject) {
        val id = task.getValue("task_id").jsonPrimitive.content
        require(tasks.size < MAX_TASKS || id in tasks) { "Task ledger full; archive explicitly before accepting new requests" }
        val request = (task["request_id"] as? JsonPrimitive)?.content
        require(request == null || tasks.values.none { it["request_id"]?.jsonPrimitive?.content == request && it["task_id"]?.jsonPrimitive?.content != id }) { "Request id already belongs to another task" }
        tasks[id] = task
    }
    private fun load(): LinkedHashMap<String, JsonObject> {
        val tasks = linkedMapOf<String, JsonObject>()
        if (!file.exists()) return tasks
        // Corruption fails closed, including for a store opened before the file became corrupt.
        Json.parseToJsonElement(file.readText()).jsonArray.forEach { item ->
            val task = item.jsonObject
            val id = task.getValue("task_id").jsonPrimitive.content
            require(id !in tasks) { "Duplicate task id in ledger" }
            insert(tasks, task)
        }
        return tasks
    }
    private fun <T> transaction(block: (LinkedHashMap<String, JsonObject>) -> T): T = synchronized(pathLock) {
        file.parentFile?.mkdirs()
        // JVM locks prevent OverlappingFileLockException; the OS lock also serializes separate app processes.
        RandomAccessFile(File(file.path + ".lock"), "rw").use { lockFile ->
            val lock = lockFile.channel.lock()
            try { block(load()) } finally { lock.release() }
        }
    }
    private fun persist(tasks: Map<String, JsonObject>) {
        // Separate staging paths prevent collision even if future callers bypass process-local coordination.
        val temporary = File.createTempFile("task-ledger-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(JsonArray(tasks.values.toList()).toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    companion object {
        const val MAX_TASKS = 2000
        private val pathLocks = ConcurrentHashMap<String, Any>()
        private val TERMINAL_STATES = setOf("succeeded", "failed", "cancelled", "timed_out", "interrupted")
    }
}
