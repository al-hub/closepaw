package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.*

/** Bounded metadata only: no screen strings, fingerprints, package names or exception messages. */
internal object ControlDiagnostics {
    private val events = ArrayDeque<JsonObject>()
    private val traces = ArrayDeque<JsonObject>()
    @Synchronized fun record(stage: String, reason: String, elapsedMs: Long = 0, taskId: String? = null,
        metrics: JsonObject = buildJsonObject {}) {
        if (stage == "sequence_trace") {
            if (traces.size >= 4) traces.removeFirst()
            traces.addLast(metrics)
            return
        }
        if (events.size >= 64) events.removeFirst()
        events.addLast(buildJsonObject {
            put("timestamp_ms", System.currentTimeMillis()); put("stage", stage); put("reason_code", reason)
            put("elapsed_ms", elapsedMs.coerceAtLeast(0)); taskId?.let { put("task_id", it.take(128)) }
            put("metrics", metrics)
        })
    }
    /** Export-safe projection subset. Both MCP and diagnostic ZIP must serialize this exact object. */
    /** Canonical redacted export payload; both remote reads and ZIP serialization use it. */
    @Synchronized fun projectionExportJson(): String = projectionSnapshot().toString()

    @Synchronized fun projectionSnapshot(): JsonObject = buildJsonObject {
        val permittedNumbers = setOf("attempt_id", "null_reads", "stale_reads", "uptime_fresh_elapsed_stale",
            "timestamp_same", "timestamp_increasing", "timestamp_decreasing", "consent_generation", "timeout_ms", "poll_ms", "tuning_revision")
        val permittedCategories = setOf("request_id", "last_observation", "first_age_bucket", "last_age_bucket",
            "clock_gap_bucket", "last_uptime_age_bucket", "source_clock_contract", "freshness_proof")
        val permittedBooleans = setOf("lease_active_after")
        val permittedClockNumbers = setOf("image_timestamp_ns", "acquire_started_elapsed_ms",
            "received_elapsed_ms", "received_uptime_ms", "sample_finished_elapsed_ms")
        val projection = events.filter { it["stage"]?.jsonPrimitive?.content in setOf("projection_frame_acquire", "projection_observe_result") }
            .takeLast(16).map { event ->
                val rawMetrics = event["metrics"] as? JsonObject ?: JsonObject(emptyMap())
                val safeMetrics = buildJsonObject {
                    for ((name, value) in rawMetrics) {
                        if (name in permittedNumbers && value is JsonPrimitive && !value.isString && value.longOrNull != null)
                            put(name, value)
                        if (name in permittedBooleans && value is JsonPrimitive && value.booleanOrNull != null)
                            put(name, value)
                        if (name in permittedCategories && value is JsonPrimitive && value.isString &&
                            value.content.length <= 40 && value.content.matches(Regex("[a-z0-9_]+")))
                            put(name, value)
                        if (name in setOf("first_clock_sample", "last_clock_sample") && value is JsonObject)
                            put(name, buildJsonObject {
                                for ((clockName, clockValue) in value)
                                    if (clockName in permittedClockNumbers && clockValue is JsonPrimitive &&
                                        !clockValue.isString && clockValue.longOrNull != null) put(clockName, clockValue)
                            })
                    }
                }
                buildJsonObject {
                    put("stage", event["stage"]?.jsonPrimitive?.content ?: "projection_frame_acquire")
                    event["timestamp_ms"]?.jsonPrimitive?.longOrNull?.let { put("timestamp_ms", it) }
                    event["reason_code"]?.jsonPrimitive?.content?.takeIf {
                        it.length <= 48 && it.matches(Regex("[a-z0-9_]+"))
                    }?.let { put("reason_code", it) }
                    event["elapsed_ms"]?.jsonPrimitive?.longOrNull?.let { put("elapsed_ms", it) }
                    put("metrics", safeMetrics)
                }
            }
        put("schema_version", 1)
        put("stored_events", projection.size)
        put("events", JsonArray(projection))
    }
    @Synchronized fun snapshot(): JsonObject = buildJsonObject {
        put("sequence_traces", JsonArray(traces.toList())); put("stored_events", events.size); put("events", JsonArray(events.toList()))
    }
}
