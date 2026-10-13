package ai.closepaw.chatgpt.operations

import java.io.File
import kotlinx.serialization.json.*

/** Durable request ledger: interrupted tasks are unknown, never automatically replayed. */
internal class OperationTaskStore(private val file: File) {
    private val tasks = linkedMapOf<String, JsonObject>()
    init {
        if (file.exists()) {
            // A corrupt ledger must fail closed: losing dedup history could replay a destructive request.
            val saved = Json.parseToJsonElement(file.readText()).jsonArray
            saved.forEach { item ->
                var task = item.jsonObject
                if (task["state"]?.jsonPrimitive?.content in setOf("queued", "running", "cancel_requested")) {
                    task = JsonObject(task + mapOf("state" to JsonPrimitive("outcome_unknown"), "summary" to JsonPrimitive("ClosePaw restarted; underlying execution outcome is not confirmed. Do not automatically retry.")))
                }
                tasks[task.getValue("task_id").jsonPrimitive.content] = task
            }
            persist()
        }
    }
    @Synchronized fun get(id: String): JsonObject? = tasks[id]
    @Synchronized fun byRequest(id: String): JsonObject? = tasks.values.firstOrNull { it["request_id"]?.jsonPrimitive?.content == id }
    @Synchronized fun put(task: JsonObject) {
        require(tasks.size < MAX_TASKS || task.getValue("task_id").jsonPrimitive.content in tasks) { "Task ledger full; archive explicitly before accepting new requests" }
        val id = task.getValue("task_id").jsonPrimitive.content
        val previous = tasks[id]
        tasks[id] = task
        try { persist() } catch (error: Exception) { if(previous == null) tasks.remove(id) else tasks[id] = previous; throw error }
    }
    @Synchronized fun update(id: String, values: Map<String, JsonElement>) {
        val previous = tasks[id] ?: return
        put(JsonObject(previous + values))
    }
    /** Atomic terminal guard shared by execution completion and cancellation. */
    @Synchronized fun updateActive(id: String, values: Map<String, JsonElement>): Boolean {
        val previous = tasks[id] ?: return false
        if (previous["state"]?.jsonPrimitive?.content in setOf("succeeded", "failed", "cancelled")) return false
        put(JsonObject(previous + values))
        return true
    }
    private fun persist() {
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        java.io.FileOutputStream(temporary).use { stream ->
            stream.write(JsonArray(tasks.values.toList()).toString().toByteArray(Charsets.UTF_8)); stream.fd.sync()
        }
        check(temporary.renameTo(file)) { "Unable to atomically persist task ledger" }
    }
    companion object { const val MAX_TASKS = 2000 }
}
