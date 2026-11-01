package ai.closepaw.chatgpt.operations

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Read-only preflight for a future lossless historical migration.
 *
 * This is deliberately not a migration or recovery action. Callers must not
 * remove any legacy records based on this report. A future migration must
 * independently verify a durable destination and global dedup index.
 */
internal object OperationTaskHistoryPreflight {
    data class Result(
        val count: Int,
        val retainedTerminal: Int,
        val mutableOrUnknown: Int,
        val requestIdentities: Int,
    )

    private val terminal = setOf("succeeded", "failed", "cancelled", "timed_out", "interrupted")

    fun inspect(records: Collection<JsonObject>): Result {
        val taskIds = HashSet<String>()
        val requestIds = HashSet<String>()
        var retainedTerminal = 0
        var mutableOrUnknown = 0
        records.forEach { task ->
            val taskId = (task["task_id"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.contentOrNull
            require(!taskId.isNullOrBlank() && taskIds.add(taskId)) { "invalid_or_duplicate_task_identity" }
            val rawRequest = task["request_id"]
            if (rawRequest != null && rawRequest !is kotlinx.serialization.json.JsonNull) {
                val request = (rawRequest as? JsonPrimitive)
                    ?.takeIf { it.isString }?.contentOrNull
                require(request != null && requestIds.add(request)) { "invalid_or_duplicate_request_identity" }
            }
            val rawState = task["state"]
            val state = when (rawState) {
                null, kotlinx.serialization.json.JsonNull -> null
                is JsonPrimitive -> {
                    require(rawState.isString) { "invalid_state" }
                    rawState.content
                }
                else -> throw IllegalArgumentException("invalid_state")
            }
            if (state in terminal) retainedTerminal++ else mutableOrUnknown++
        }
        return Result(records.size, retainedTerminal, mutableOrUnknown, requestIds.size)
    }
}
