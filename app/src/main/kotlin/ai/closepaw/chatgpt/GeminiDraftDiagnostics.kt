package ai.closepaw.chatgpt

import android.util.Log
import java.io.File
import kotlinx.serialization.json.*

/** Bounded metadata-only history survives service/process restarts; never accepts UI text. */
internal class GeminiDraftDiagnostics(private val file: File) {
    @Synchronized fun record(event: DraftDiagnostic) {
        val entry = JsonObject(event.toJson() + ("timestamp_ms" to JsonPrimitive(System.currentTimeMillis())))
        val events = load().takeLast(63) + entry
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(JsonArray(events).toString())
        check(temp.renameTo(file)) { "Diagnostic metadata could not be persisted" }
        runCatching { Log.i("ClosePawGeminiDraft", entry.toString()) }
    }
    @Synchronized fun snapshot(): JsonObject = buildJsonObject {
        put("events", JsonArray(load())); put("capacity", 64)
    }
    private fun load(): List<JsonElement> = runCatching {
        if (file.exists() && file.length() <= 64 * 1024) Json.parseToJsonElement(file.readText()).jsonArray.toList() else emptyList()
    }.getOrDefault(emptyList())
}
