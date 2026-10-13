package ai.closepaw.chatgpt.operations

import kotlinx.serialization.json.*

/** Deliberate JSON Schema subset. Unsupported constraints fail registration rather than being silently ignored. */
internal object OperationSchemaValidator {
    private val keys = setOf("type", "properties", "required", "additionalProperties", "items", "enum", "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems", "description", "title", "default")
    fun checkSchema(schema: JsonObject) {
        require(schema.keys.all { it in keys }) { "Unsupported schema keyword" }
        val type = schema["type"]?.jsonPrimitive?.content
        require(type in setOf("object", "array", "string", "integer", "number", "boolean", "null")) { "Explicit supported schema type required" }
        (schema["properties"] as? JsonObject)?.values?.forEach { checkSchema(it.jsonObject) }
        schema["items"]?.let { checkSchema(it.jsonObject) }
        require(schema["additionalProperties"] == null || (schema["additionalProperties"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull != null } == true) { "additionalProperties must be boolean" }
        schema["required"]?.let { required ->
            require(required is JsonArray && required.all { it is JsonPrimitive && it.isString }) { "required must contain property names" }
        }
        schema["enum"]?.let { require(it is JsonArray && it.isNotEmpty()) { "enum must be a nonempty array" } }
        listOf("minLength", "maxLength", "minItems", "maxItems").forEach { key ->
            schema[key]?.let { value -> require(value is JsonPrimitive && !value.isString && value.intOrNull != null && value.int >= 0) { "$key must be a nonnegative integer" } }
        }
        listOf("minimum", "maximum").forEach { key ->
            schema[key]?.let { value -> require(value is JsonPrimitive && !value.isString && value.doubleOrNull?.isFinite() == true) { "$key must be finite" } }
        }
    }
    fun validate(schema: JsonObject, input: JsonElement, path: String = "input"): String? {
        val type = schema["type"]?.jsonPrimitive?.content
        val primitive = input as? JsonPrimitive
        val matches = when(type) {
            "object" -> input is JsonObject
            "array" -> input is JsonArray
            "string" -> primitive?.isString == true
            "boolean" -> primitive?.isString == false && primitive.booleanOrNull != null
            "integer" -> primitive?.isString == false && primitive.longOrNull != null
            "number" -> primitive?.isString == false && primitive.doubleOrNull?.isFinite() == true
            "null" -> input == JsonNull
            else -> false
        }
        if (!matches) return "$path must be $type"
        (schema["enum"] as? JsonArray)?.let { if (input !in it) return "$path is not an allowed value" }
        if (input is JsonObject) {
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            (schema["required"] as? JsonArray)?.forEach { if (it.jsonPrimitive.content !in input) return "$path missing ${it.jsonPrimitive.content}" }
            if (schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false && input.keys.any { it !in properties }) return "$path has unknown properties"
            input.forEach { (key, value) -> (properties[key] as? JsonObject)?.let { validate(it, value, "$path.$key")?.let { error -> return error } } }
        }
        if (input is JsonArray) {
            schema["minItems"]?.jsonPrimitive?.intOrNull?.let { if (input.size < it) return "$path has too few items" }
            schema["maxItems"]?.jsonPrimitive?.intOrNull?.let { if (input.size > it) return "$path has too many items" }
            (schema["items"] as? JsonObject)?.let { itemSchema -> input.forEachIndexed { index, value -> validate(itemSchema,value,"$path[$index]")?.let { return it } } }
        }
        if (type == "string") {
            val length = primitive!!.content.length
            schema["minLength"]?.jsonPrimitive?.intOrNull?.let { if(length < it) return "$path is too short" }
            schema["maxLength"]?.jsonPrimitive?.intOrNull?.let { if(length > it) return "$path is too long" }
        }
        if (type == "integer" || type == "number") {
            val number = primitive!!.double
            schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { if(number < it) return "$path below minimum" }
            schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { if(number > it) return "$path above maximum" }
        }
        return null
    }
}
