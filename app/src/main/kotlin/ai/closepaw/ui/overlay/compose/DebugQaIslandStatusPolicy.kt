package ai.closepaw.ui.overlay.compose

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Display only provenance-backed, unexpired *local Debug QA* status, never ChatGPT's state. */
internal data class DebugQaIslandStatus(val state: String, val stage: String? = null) {
    companion object { val UNKNOWN = DebugQaIslandStatus("UNKNOWN") }
}

internal object DebugQaIslandStatusPolicy {
    private val safeStage = Regex("[a-z][a-z0-9_]{0,79}")
    fun decode(json: String?, elapsedMs: Long, epochMs: Long): DebugQaIslandStatus {
        if (json.isNullOrBlank() || json.length > 2048) return DebugQaIslandStatus.UNKNOWN
        return runCatching {
            val fields = Json.parseToJsonElement(json).jsonObject
            val state = fields["state"]?.jsonPrimitive?.contentOrNull
                ?: return@runCatching DebugQaIslandStatus.UNKNOWN
            val maxAge = when (state) {
                "RUNNING" -> 5_000L
                "WAITING" -> 300_000L
                "COMPLETED", "FAILED", "CANCELLED" -> 60_000L
                else -> return@runCatching DebugQaIslandStatus.UNKNOWN
            }
            val lastElapsed = fields["updated_at_elapsed_ms"]?.jsonPrimitive?.longOrNull
                ?: return@runCatching DebugQaIslandStatus.UNKNOWN
            val lastEpoch = fields["updated_at_epoch_ms"]?.jsonPrimitive?.longOrNull
                ?: return@runCatching DebugQaIslandStatus.UNKNOWN
            // Both clock domains must agree. A stale file, reboot or clock reset is UNKNOWN.
            if (elapsedMs - lastElapsed !in 0L..maxAge ||
                epochMs - lastEpoch !in 0L..maxAge) DebugQaIslandStatus.UNKNOWN
            else DebugQaIslandStatus(state, fields["stage"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { safeStage.matches(it) })
        }.getOrDefault(DebugQaIslandStatus.UNKNOWN)
    }
}
