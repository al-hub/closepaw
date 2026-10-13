package ai.closepaw.chatgpt

import kotlinx.serialization.json.*

/** Fixed external contract; operation-specific rules are discovered as data. */
internal fun interface McpOperationTools {
    fun call(name: String, arguments: JsonObject): JsonObject
}

internal object McpOperationContract {
    val names = setOf("discover_operations", "query", "execute", "get_task", "cancel_task")
    private val allowed = mapOf(
        "discover_operations" to setOf("operation_id", "search", "offset", "limit"),
        "query" to setOf("operation_id", "operation_version", "schema_hash", "input_json"),
        "execute" to setOf("operation_id", "operation_version", "schema_hash", "input_json", "request_id"),
        "get_task" to setOf("task_id", "offset", "limit"),
        "cancel_task" to setOf("task_id", "request_id"),
    )
    fun arguments(name: String, raw: JsonObject): JsonObject {
        require(raw.keys.all { it in allowed.getValue(name) }) { "Unknown argument" }
        val schema = definition(name)["inputSchema"]!!.jsonObject
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        require(raw.keys.containsAll(required)) { "Missing required argument" }
        val properties = schema["properties"]!!.jsonObject
        raw.forEach { (key, value) ->
            val primitive = value as? JsonPrimitive ?: error("Argument must be primitive")
            when (properties.getValue(key).jsonObject["type"]!!.jsonPrimitive.content) {
                "string" -> require(primitive.isString && primitive.content.length <= 65536) { "Expected bounded string" }
                "integer" -> require(!primitive.isString && primitive.intOrNull != null) { "Expected integer" }
            }
        }
        if (name != "query" && name != "execute") return raw
        val input = Json.parseToJsonElement(raw.getValue("input_json").jsonPrimitive.content) as? JsonObject
            ?: error("input_json must encode a JSON object")
        return JsonObject(raw.filterKeys { it != "input_json" } + ("input" to input))
    }
    fun definition(name: String): JsonObject = buildJsonObject {
        put("name", name)
        put("description", when(name) {
            "discover_operations" -> "Discover currently registered ClosePaw operations, current version/schema hash, detailed input rules and readiness. Discover before invoking. New app and operation IDs do not change these tools."
            "query" -> "Invoke a discovered read operation. Supply its version/hash and input_json as a JSON object encoded in a string. Summarize captured content only."
            "execute" -> "Submit a discovered operation that may change files or apps or send data. Requires local execution permission. Use a unique request_id; repeat identical IDs only to retrieve the same task. Inspect get_task for confirmed result; never assume queued means succeeded."
            "get_task" -> "Read confirmed task state and paged stdout/stderr, exit code or outcome uncertainty. Use next_offset while has_more."
            else -> "Request task cancellation. Treat cancelled only when underlying executor confirms; unsupported cancellation does not mean stopped."
        })
        put("inputSchema", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                allowed.getValue(name).sorted().forEach { key -> put(key, buildJsonObject {
                    put("type", if(key in setOf("offset", "limit", "operation_version")) "integer" else "string")
                    if(key == "input_json") put("description", "Operation input JSON object encoded as a string, following discover_operations input_schema.")
                }) }
            })
            put("required", JsonArray(when(name) {
                "query" -> listOf("operation_id", "operation_version", "schema_hash", "input_json")
                "execute" -> listOf("operation_id", "operation_version", "schema_hash", "input_json", "request_id")
                "get_task", "cancel_task" -> listOf("task_id")
                else -> emptyList()
            }.map { JsonPrimitive(it) }))
            put("additionalProperties", false)
        })
        put("annotations", buildJsonObject {
            val readOnly = name !in setOf("execute", "cancel_task")
            put("readOnlyHint", readOnly); put("destructiveHint", !readOnly)
            put("idempotentHint", name != "execute"); put("openWorldHint", true)
        })
    }
}
