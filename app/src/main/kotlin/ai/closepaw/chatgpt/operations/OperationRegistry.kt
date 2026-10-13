package ai.closepaw.chatgpt.operations

import java.security.MessageDigest
import kotlinx.serialization.json.*

internal data class OperationReadiness(val ready: Boolean = true, val reason: String? = null)
internal data class OperationDescriptor(
    val id: String,
    val description: String,
    val version: Int = 1,
    val inputSchema: JsonObject,
    val readOnly: Boolean = true,
    val permissions: List<String> = emptyList(),
    val outputSchema: JsonObject = buildJsonObject { put("type", "object") },
    val effects: List<String> = emptyList(),
    val cancellationSupported: Boolean = false,
    val readiness: () -> OperationReadiness = { OperationReadiness() },
) {
    val schemaHash: String get() = operationHash(canonicalJson(inputSchema))
    fun safeReadiness(): OperationReadiness = try { readiness() } catch (_: Exception) {
        OperationReadiness(false, "Readiness check failed; inspect local diagnostics.")
    }
    fun toJson(includeSchema: Boolean = true): JsonObject {
        val state = safeReadiness()
        return buildJsonObject {
            put("operation_id", id); put("description", description); put("operation_version", version)
            put("schema_hash", schemaHash); put("output_schema_hash", operationHash(canonicalJson(outputSchema))); if (includeSchema) { put("input_schema", inputSchema); put("output_schema", outputSchema) }; put("read_only", readOnly)
            put("effects", JsonArray(effects.map { JsonPrimitive(it) })); put("cancellation_supported", cancellationSupported)
            put("permissions", JsonArray(permissions.map { JsonPrimitive(it) })); put("ready", state.ready)
            state.reason?.let { put("readiness_reason", it) }
        }
    }
}
internal data class OperationInvocation(val taskId: String, val input: JsonObject)
internal data class OperationResult(
    val data: JsonObject = JsonObject(emptyMap()),
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val success: Boolean = true,
    val summary: String = "Operation completed.",
)
internal interface OperationHandler {
    suspend fun invoke(invocation: OperationInvocation): OperationResult
    // True only after the underlying executor confirms that the command stopped.
    suspend fun cancel(taskId: String): Boolean = false
}
internal data class RegisteredOperation(val descriptor: OperationDescriptor, val handler: OperationHandler)
internal class OperationRegistry {
    private val entries = linkedMapOf<String, RegisteredOperation>()
    @Synchronized fun register(descriptor: OperationDescriptor, handler: OperationHandler) {
        require(descriptor.id.matches(Regex("[a-zA-Z0-9_.-]{1,128}"))) { "Invalid operation id" }
        require(descriptor.version > 0)
        require(!entries.containsKey(descriptor.id)) { "Duplicate operation id: ${descriptor.id}" }
        OperationSchemaValidator.checkSchema(descriptor.inputSchema)
        OperationSchemaValidator.checkSchema(descriptor.outputSchema)
        entries[descriptor.id] = RegisteredOperation(descriptor, handler)
    }
    @Synchronized fun get(id: String): RegisteredOperation? = entries[id]
    @Synchronized fun all(): List<RegisteredOperation> = entries.values.toList()
}
internal fun canonicalJson(value: JsonElement): String = when(value) {
    is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { JsonPrimitive(it).toString() + ":" + canonicalJson(value.getValue(it)) }
    is JsonArray -> value.joinToString(",", "[", "]") { canonicalJson(it) }
    else -> value.toString()
}
internal fun operationHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
