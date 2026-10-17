package ai.closepaw.tailscale

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.*

/** Only bounded recovery metadata; no hosts, addresses, shell text, tokens or account data. */
internal class TailscaleDiagnostics(private val file: File) {
    private val lock = locks.computeIfAbsent(file.canonicalPath) { Any() }
    fun record(outcome: TailscaleOutcome): Unit = synchronized(lock) {
        val events = load().takeLast(19) + buildJsonObject {
            put("at_ms", System.currentTimeMillis()); put("state", outcome.state)
            put("connect_requested", outcome.connectRequested)
            put("target_reachable", outcome.probe?.reachable == true)
            put("probe_reason", outcome.probe?.reason ?: "not_probed")
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(JsonArray(events).toString())
        check(temp.renameTo(file)) { "Unable to save recovery metadata" }
        Unit
    }
    fun snapshot(): JsonObject = synchronized(lock) { buildJsonObject { put("events", JsonArray(load())); put("capacity", 20) } }
    private fun load(): List<JsonElement> = runCatching {
        if (file.exists() && file.length() <= 16384) Json.parseToJsonElement(file.readText()).jsonArray.toList() else emptyList()
    }.getOrDefault(emptyList())
    companion object { private val locks = ConcurrentHashMap<String, Any>() }
}
