package ai.closepaw.chatgpt

import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/** Holds only a Gemini get_task read for up to 8 s, returning as soon as the task is terminal.
 * No schema change, resubmission, or added delay for terminal pages and other operations. */
internal class GeminiTaskAwaiter(
    private val read: (JsonObject) -> JsonObject,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun get(arguments: JsonObject): JsonObject {
        var task = read(arguments)
        repeat(80) {
            if (task["operation_id"]?.jsonPrimitive?.content != "gemini.question.ask" ||
                task["state"]?.jsonPrimitive?.content !in setOf("queued", "running")) return task
            pause(100)
            task = read(arguments)
        }
        return task
    }
}
