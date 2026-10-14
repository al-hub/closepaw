package ai.closepaw.chatgpt

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.*

/** Bounded metadata-only history survives service/process restarts; never accepts UI text. */
internal class GeminiDraftDiagnostics(private val file: File) {
    private val lock = locks.computeIfAbsent(file.canonicalPath) { Any() }
    fun record(event: DraftDiagnostic): Unit = synchronized(lock) {
        val entry = JsonObject(event.toJson() + ("timestamp_ms" to JsonPrimitive(System.currentTimeMillis())))
        val events = load().takeLast(63) + entry
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(JsonArray(events).toString())
        check(temp.renameTo(file)) { "Diagnostic metadata could not be persisted" }
        runCatching { Log.i("ClosePawGeminiDraft", entry.toString()) }
        Unit
    }
    fun snapshot(): JsonObject = synchronized(lock) { buildJsonObject {
        put("events", JsonArray(load())); put("capacity", 64)
    } }
    companion object { private val locks = ConcurrentHashMap<String, Any>() }
    private fun load(): List<JsonElement> = runCatching {
        if (file.exists() && file.length() <= 64 * 1024) Json.parseToJsonElement(file.readText()).jsonArray.toList() else emptyList()
    }.getOrDefault(emptyList())
}
