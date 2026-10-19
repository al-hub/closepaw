package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.*

/** Declarative configuration only. No downloaded code, coordinates or weakening of native security. */
internal class RuntimeControlSettings(private val read: () -> String?, private val persist: (String) -> Unit) {
    data class Snapshot(val revision: Long, val config: JsonObject)
    companion object {
        fun defaults() = buildJsonObject {
            put("node_budget", 4096); put("output_limit", 50); put("apps", buildJsonObject {})
        }
        fun validate(config: JsonObject): Boolean {
            if (config.keys != setOf("node_budget", "output_limit", "apps")) return false
            val budget = (config["node_budget"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return false
            val output = (config["output_limit"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return false
            val apps = config["apps"] as? JsonObject ?: return false
            return budget in 512..8192 && output in 10..100 && apps.size <= 64 && apps.all { (pkg, access) ->
                pkg.length <= 256 && Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(pkg) &&
                    (access as? JsonPrimitive)?.takeIf { it.isString }?.content in setOf("deny", "read", "interact")
            }
        }
    }
    @Synchronized fun snapshot(): Snapshot {
        val raw = read() ?: return Snapshot(0, defaults())
        val record = Json.parseToJsonElement(raw).jsonObject
        val config = record.getValue("config").jsonObject
        check(validate(config)) { "runtime_configuration_invalid" }
        return Snapshot(record.getValue("revision").jsonPrimitive.long, config)
    }
    @Synchronized fun change(expected: Long, action: String, config: JsonObject?): Snapshot {
        val old = snapshot()
        require(old.revision == expected) { "configuration_revision_changed" }
        val next = if (action == "apply") config ?: error("configuration_required") else {
            require(action == "rollback") { "configuration_action_invalid" }
            val record = read()?.let { Json.parseToJsonElement(it).jsonObject }
            record?.get("previous")?.jsonObject ?: error("configuration_rollback_unavailable")
        }
        require(validate(next)) { "configuration_invalid" }
        val updated = Snapshot(Math.addExact(old.revision, 1), next)
        persist(buildJsonObject {
            put("revision", updated.revision); put("config", next); put("previous", old.config)
        }.toString())
        return updated
    }
    fun access(pkg: String, normal: Boolean, operation: String): Boolean {
        val setting = snapshot().config.getValue("apps").jsonObject[pkg]?.jsonPrimitive?.content
        return when (setting) {
            "deny" -> false
            "interact" -> true
            "read" -> operation != "interact"
            else -> normal || operation == "launch"
        }
    }
}
