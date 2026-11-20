package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.operations.operationHash
import kotlinx.serialization.json.*

/** Local comparison only. Classify known Chrome accessibility regions during the existing walk. */
internal class ChromeTreeRegions {
    enum class Region { EDITOR, SUGGESTIONS, OTHER }
    data class Fingerprints(val editor: String?, val suggestions: String?, val other: String?,
        val suggestionShape: String? = null)

    companion object {
        fun regionOf(resource: String, inherited: Region): Region = when (resource) {
            "com.android.chrome:id/url_bar" -> Region.EDITOR
            "com.android.chrome:id/omnibox_suggestions_dropdown" -> Region.SUGGESTIONS
            else -> inherited
        }
    }

    private val nodes = Region.entries.associateWith { mutableListOf<JsonObject>() }

    fun add(region: Region, node: JsonObject) { nodes.getValue(region).add(node) }

    fun fingerprints() = Fingerprints(hash(Region.EDITOR), hash(Region.SUGGESTIONS), hash(Region.OTHER),
        hash(Region.SUGGESTIONS, shapeOnly = true))

    private fun hash(region: Region, shapeOnly: Boolean = false): String? {
        val members = nodes.getValue(region)
        if (members.isEmpty()) return null
        // IDs from one region must not change merely because another region added a node.
        val ids = members.mapIndexed { index, node -> node.getValue("id").jsonPrimitive.int to index }.toMap()
        return operationHash(buildString {
            for ((index, node) in members.withIndex()) {
                val parent = node.getValue("parent").jsonPrimitive.int
                if (shapeOnly) {
                    // Keep absolute indices and parents, geometry and interaction
                    // properties. Only caption fields may differ for this guard.
                    append(JsonObject(node - setOf("text_hash", "description_hash", "hint")))
                } else {
                    append(JsonObject(node + mapOf("id" to JsonPrimitive(index), "parent" to JsonPrimitive(ids[parent] ?: -1))))
                }
                append('\n')
            }
        })
    }
}
