package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.*

/** Bounded metadata only: no screen strings, fingerprints, package names or exception messages. */
internal object ControlDiagnostics {
    private val events = ArrayDeque<JsonObject>()
    @Synchronized fun record(stage: String, reason: String, elapsedMs: Long = 0, taskId: String? = null,
        metrics: JsonObject = buildJsonObject {}) {
        if (events.size >= 64) events.removeFirst()
        events.addLast(buildJsonObject {
            put("timestamp_ms", System.currentTimeMillis()); put("stage", stage); put("reason_code", reason)
            put("elapsed_ms", elapsedMs.coerceAtLeast(0)); taskId?.let { put("task_id", it.take(128)) }
            put("metrics", metrics)
        })
    }
    @Synchronized fun snapshot(): JsonObject = buildJsonObject {
        put("stored_events", events.size); put("events", JsonArray(events.toList()))
    }
}
