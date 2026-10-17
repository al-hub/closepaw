package ai.closepaw.chatgpt.operations

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Routes every operation through the same descriptor, validator, policy, and executor. */
internal class OperationDispatcher(
    private val registry: OperationRegistry,
    private val store: OperationTaskStore,
    private val scope: CoroutineScope,
    private val operationAllowed: (String) -> Boolean = { true },
    private val executeAllowed: () -> Boolean = { true },
) {
    fun discover(arguments: JsonObject = JsonObject(emptyMap())): JsonObject {
        val filter = (arguments["operation_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (arguments["operation_id"] != null && filter == null) return error("invalid_operation_id", "operation_id must be a string.")
        if (filter != null && registry.get(filter) == null) return error("unknown_operation", "Operation is not registered.")
        val search = (arguments["search"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        if (search.length > 256 || (arguments["search"] != null && (arguments["search"] as? JsonPrimitive)?.isString != true)) return error("invalid_search", "search must be a string of at most 256 characters.")
        val offset = (arguments["offset"] as? JsonPrimitive)?.intOrNull ?: 0
        val limit = (arguments["limit"] as? JsonPrimitive)?.intOrNull ?: 20
        if (offset < 0 || limit !in 1..50 || (arguments["offset"] != null && (arguments["offset"] as? JsonPrimitive)?.intOrNull == null) || (arguments["limit"] != null && (arguments["limit"] as? JsonPrimitive)?.intOrNull == null)) return error("invalid_page", "offset must be nonnegative and limit between 1 and 50.")
        val matches = registry.all().filter { entry ->
            (filter == null || entry.descriptor.id == filter) && (filter != null || search.isBlank() || entry.descriptor.id.contains(search, ignoreCase = true) || entry.descriptor.description.contains(search, ignoreCase = true))
        }
        return buildJsonObject {
            put("status", "succeeded"); put("summary", if(filter == null) "Discover operation summaries; provide operation_id to obtain the input schema." else "Current operation schema and readiness.")
            put("protocol_version", 1); put("offset", offset); put("limit", limit); put("total", matches.size)
            put("operations", JsonArray(matches.drop(offset).take(limit).map { it.descriptor.toJson(includeSchema = filter != null) }))
            val more = offset.toLong() + limit < matches.size
            put("has_more", more); if(more) put("next_offset", offset.toLong() + limit)
        }
    }
    fun query(arguments: JsonObject): JsonObject {
        val operation = resolve(arguments) ?: return error("unknown_operation", "Operation is not registered.")
        if (!operation.descriptor.readOnly) return error("write_operation_requires_execute", "Write operations require execute.")
        validate(operation, arguments)?.let { return it }
        return try {
            val result = runBlocking { withTimeout(60_000) { operation.handler.invoke(OperationInvocation(UUID.randomUUID().toString(), arguments.getValue("input").jsonObject)) } }
            val bounded = resultJson(result)
            if (OperationSchemaValidator.validate(operation.descriptor.outputSchema, bounded) != null)
                error("invalid_output", "Operation returned output that does not match its registered schema.")
            else bounded
        } catch (_: Exception) { error("operation_failed", "Read operation failed; inspect local diagnostics.") }
    }
    @Synchronized fun execute(arguments: JsonObject): JsonObject {
        val operation = resolve(arguments) ?: return error("unknown_operation", "Operation is not registered.")
        if (!executeAllowed() || !operationAllowed(operation.descriptor.id)) return error("permission_denied", "Execution is disabled for this operation. Enable it locally in ClosePaw.")
        val request = (arguments["request_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (request.isNullOrBlank() || request.length > 128) return error("invalid_request_id", "request_id must be a nonempty string of at most 128 characters.")
        validate(operation, arguments)?.let { return it }
        val fingerprint = operationHash(canonicalJson(buildJsonObject {
            put("operation_id", operation.descriptor.id); put("operation_version", operation.descriptor.version)
            put("schema_hash", operation.descriptor.schemaHash); put("input", arguments.getValue("input"))
        }))
        store.byRequest(request)?.let {
            return if (it["fingerprint"]?.jsonPrimitive?.content == fingerprint) page(it, 0, DEFAULT_PAGE)
                else error("request_id_conflict", "request_id was already used with different input or operation schema.")
        }
        val taskId = UUID.randomUUID().toString()
        val task = buildJsonObject {
            put("task_id", taskId); put("request_id", request); put("fingerprint", fingerprint)
            put("operation_id", operation.descriptor.id); put("operation_version", operation.descriptor.version)
            put("schema_hash", operation.descriptor.schemaHash); put("state", "queued")
            put("created_at_ms", System.currentTimeMillis()); put("summary", "Operation accepted; use get_task to confirm its outcome.")
        }
        val claimed = try { store.claim(task) } catch (_: Exception) { return error("task_storage_failed", "Unable to durably record request; operation was not started.") }
        if (!claimed.created) return if (claimed.conflict) error("request_id_conflict", "request_id was already used with different input or operation schema.") else page(claimed.task, 0, DEFAULT_PAGE)
        scope.launch {
            try {
                val shouldStart = synchronized(this@OperationDispatcher) {
                    if (store.get(taskId)?.get("state")?.jsonPrimitive?.content == "cancelled") false
                    else { store.update(taskId, mapOf("state" to JsonPrimitive("running"))); true }
                }
                if (!shouldStart) return@launch
                // Recheck after queuing: local permission may have been revoked.
                if (!executeAllowed() || !operationAllowed(operation.descriptor.id)) {
                    store.updateActive(taskId, mapOf("state" to JsonPrimitive("failed"), "summary" to JsonPrimitive("Remote execution permission revoked before start.")))
                    return@launch
                }
                val result = operation.handler.invoke(OperationInvocation(taskId, arguments.getValue("input").jsonObject))
                val bounded = resultJson(result)
                if (OperationSchemaValidator.validate(operation.descriptor.outputSchema, bounded) != null) {
                    val invalid = error("invalid_output", "Operation may have executed, but its returned output does not match the registered schema. Do not automatically retry.")
                    store.updateActive(taskId, mapOf(
                        "state" to JsonPrimitive("outcome_unknown"), "output_validation_failed" to JsonPrimitive(true),
                        "result" to invalid, "summary" to invalid.getValue("summary"),
                    ))
                } else {
                    store.updateActive(taskId, mapOf("state" to JsonPrimitive(terminalState(result)), "result" to bounded, "summary" to bounded.getValue("summary")))
                }
            } catch (_: CancellationException) {
                store.updateActive(taskId, mapOf("state" to JsonPrimitive("outcome_unknown"), "summary" to JsonPrimitive("Execution observer stopped; command termination is not confirmed.")))
            } catch (_: Exception) {
                store.updateActive(taskId, mapOf("state" to JsonPrimitive("outcome_unknown"), "summary" to JsonPrimitive("Execution failed to return a confirmed outcome; do not automatically retry.")))
            }
        }
        return page(task, 0, DEFAULT_PAGE)
    }
    fun getTask(arguments: JsonObject): JsonObject {
        val id = (arguments["task_id"] as? JsonPrimitive)?.content ?: return error("invalid_task_id", "task_id required")
        val task = store.get(id) ?: return error("unknown_task", "Task is not recorded.")
        val offset = (arguments["offset"] as? JsonPrimitive)?.intOrNull ?: 0
        val limit = (arguments["limit"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_PAGE
        if(offset < 0 || limit !in 1..MAX_PAGE) return error("invalid_page", "offset must be nonnegative and limit between 1 and $MAX_PAGE.")
        return page(task, offset, limit)
    }
    @Synchronized fun cancelTask(arguments: JsonObject): JsonObject {
        if (!executeAllowed()) return error("permission_denied", "Remote execution is disabled.")
        val id = (arguments["task_id"] as? JsonPrimitive)?.content ?: return error("invalid_task_id", "task_id required")
        val task = store.get(id) ?: return error("unknown_task", "Task is not recorded.")
        if (!operationAllowed(task["operation_id"]?.jsonPrimitive?.content.orEmpty()))
            return error("permission_denied", "Execution is disabled for this operation. Enable it locally in ClosePaw.")
        if(task["state"]?.jsonPrimitive?.content in setOf("succeeded", "failed", "cancelled", "timed_out", "interrupted")) return page(task, 0, DEFAULT_PAGE)
        val operation = registry.get(task.getValue("operation_id").jsonPrimitive.content) ?: return error("unknown_operation", "Handler is unavailable.")
        val confirmed = try { runBlocking { withTimeout(10_000) { operation.handler.cancel(id) } } } catch (_: Exception) { false }
        if (confirmed) {
            // A completed result can race cancellation; do not overwrite a confirmed terminal result.
            store.updateActive(id, mapOf("state" to JsonPrimitive("cancelled"), "summary" to JsonPrimitive("Underlying executor confirmed cancellation.")))
        }
        return JsonObject(page(store.get(id)!!, 0, DEFAULT_PAGE) + mapOf("cancel_confirmed" to JsonPrimitive(confirmed)))
    }
    private fun resolve(arguments: JsonObject) = (arguments["operation_id"] as? JsonPrimitive)?.content?.let { registry.get(it) }
    private fun validate(operation: RegisteredOperation, arguments: JsonObject): JsonObject? {
        val descriptor = operation.descriptor
        if ((arguments["operation_version"] as? JsonPrimitive)?.intOrNull != descriptor.version) return error("schema_changed", "Rediscover the current operation version.")
        if ((arguments["schema_hash"] as? JsonPrimitive)?.content != descriptor.schemaHash) return error("schema_changed", "Rediscover the current input schema.")
        val readiness = descriptor.safeReadiness()
        if (!readiness.ready) return error("not_ready", readiness.reason ?: "Operation is not ready.")
        val input = arguments["input"] as? JsonObject ?: return error("invalid_input", "input must be an object")
        if(input.toString().length > 65_536) return error("invalid_input", "Input exceeds 65536 characters.")
        return OperationSchemaValidator.validate(descriptor.inputSchema, input)?.let { error("invalid_input", it) }
    }
    private fun terminalState(result: OperationResult): String = when {
        (result.data["outcome_unknown"] as? JsonPrimitive)?.booleanOrNull == true -> "outcome_unknown"
        (result.data["cancel_confirmed"] as? JsonPrimitive)?.booleanOrNull == true -> "cancelled"
        (result.data["timed_out"] as? JsonPrimitive)?.booleanOrNull == true -> "timed_out"
        (result.data["state"] as? JsonPrimitive)?.content == "interrupted" -> "interrupted"
        result.success -> "succeeded"
        else -> "failed"
    }
    private fun resultJson(result: OperationResult): JsonObject = buildJsonObject {
        put("status", if(result.success) "succeeded" else "failed"); put("summary", codePointSlice(result.summary, 0, 2000))
        // Keep non-output structured data bounded as well. Screen imagery is returned through existing read_app.
        if(result.data.toString().length <= MAX_DATA) put("data", result.data)
        else { put("data", JsonObject(emptyMap())); put("data_truncated", true) }
        put("stdout", codePointSlice(result.stdout, 0, MAX_OUTPUT)); put("stderr", codePointSlice(result.stderr, 0, MAX_OUTPUT))
        put("output_truncated", codePointLength(result.stdout) > MAX_OUTPUT || codePointLength(result.stderr) > MAX_OUTPUT || result.data["output_truncated"]?.jsonPrimitive?.booleanOrNull == true)
        result.exitCode?.let { put("exit_code", it) }
    }
    private fun page(task: JsonObject, offset: Int, limit: Int): JsonObject {
        val result = task["result"] as? JsonObject
        val public = task.filterKeys { it != "fingerprint" }
        return buildJsonObject {
            public.forEach { (key, value) -> if(key != "result") put(key,value) }
            put("status", when(task["state"]?.jsonPrimitive?.content) {
                "failed", "outcome_unknown", "timed_out", "interrupted" -> "failed"
                "queued", "running", "cancel_requested" -> "accepted"
                else -> "succeeded"
            })
            if(result != null) {
                val stdout = result["stdout"]?.jsonPrimitive?.content.orEmpty()
                val stderr = result["stderr"]?.jsonPrimitive?.content.orEmpty()
                put("result", JsonObject(result + mapOf("stdout" to JsonPrimitive(codePointSlice(stdout, offset, limit)), "stderr" to JsonPrimitive(codePointSlice(stderr, offset, limit)))))
                put("output_offset", offset); put("output_limit", limit); put("offset_unit", "unicode_code_points")
                val more = offset.toLong() + limit < maxOf(codePointLength(stdout),codePointLength(stderr))
                put("has_more", more); if(more) put("next_offset", offset.toLong()+limit)
            }
        }
    }
    private fun codePointLength(value: String): Int = value.codePointCount(0, value.length)
    private fun codePointSlice(value: String, offset: Int, limit: Int): String {
        val count = codePointLength(value)
        if (offset >= count) return ""
        val start = value.offsetByCodePoints(0, offset)
        val end = value.offsetByCodePoints(start, minOf(limit, count - offset))
        return value.substring(start, end)
    }
    private fun error(code: String, summary: String) = buildJsonObject { put("status", "failed"); put("error_code", code); put("summary", summary) }
    companion object { const val DEFAULT_PAGE = 8000; const val MAX_PAGE = 16000; const val MAX_OUTPUT = 128_000; const val MAX_DATA = 32_000 }
}
