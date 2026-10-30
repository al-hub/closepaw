package ai.closepaw.chatgpt.operations

import kotlinx.serialization.json.*

/** The production discovery/validation contract, shared with real registry tests. */
internal object ProjectionTuningProtocol {
    fun requiresSessionEpoch(id: String) = id == "closepaw.projection.tuning.set"
    fun get(readiness: () -> OperationReadiness = { OperationReadiness() }) = OperationDescriptor(
        id = "closepaw.projection.tuning.get",
        description = "Read revisioned persisted frame polling timeout and interval; does not modify security freshness.",
        inputSchema = buildJsonObject {
            put("type", "object"); put("properties", buildJsonObject {}); put("additionalProperties", false)
        },
        readOnly = true,
        permissions = listOf("generic_android_control"),
        readiness = readiness,
    )
    fun set(readiness: () -> OperationReadiness = { OperationReadiness() }) = OperationDescriptor(
        id = "closepaw.projection.tuning.set",
        description = "Explicitly authorized CAS update of bounded frame polling parameters. Never alters image age, delivery expiry, permission or masking guards.",
        inputSchema = buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            put("properties", buildJsonObject {
                put("expected_revision", buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", Int.MAX_VALUE) })
                put("timeout_ms", buildJsonObject { put("type", "integer"); put("minimum", 1500); put("maximum", 3000) })
                put("poll_ms", buildJsonObject { put("type", "integer"); put("minimum", 25); put("maximum", 100) })
                // The runtime deliberately supports enum, not JSON Schema const.
                put("authorized", buildJsonObject { put("type", "boolean"); put("enum", JsonArray(listOf(JsonPrimitive(true)))) })
                put("session_id", buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 128) })
            })
            put("required", JsonArray(listOf("expected_revision", "timeout_ms", "poll_ms", "authorized", "session_id").map(::JsonPrimitive)))
        },
        readOnly = false,
        effects = listOf("persisted_projection_polling_configuration"),
        permissions = listOf("generic_android_control"),
        readiness = readiness,
    )
}
