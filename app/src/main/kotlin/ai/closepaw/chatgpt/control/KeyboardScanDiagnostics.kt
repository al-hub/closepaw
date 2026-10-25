package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.*

/** Read-only bounded diagnostics, never actionable nodes. No suggestions, editor text,
 * arbitrary descriptions or coordinates. Only otherwise recognized safe key labels.
 * Native dispatch still requires isTextEntryKey and the complete selected-IME guards. */
internal class KeyboardScanDiagnostics {
    private var visited = 0
    private var visible = 0
    private var clickable = 0
    private var textEntry = 0
    private var recognized = 0
    private val classes = linkedMapOf<String, Int>()
    private val candidates = mutableListOf<JsonObject>()
    fun record(text: String, description: String, className: String, isVisible: Boolean,
        enabled: Boolean, canClick: Boolean, textEntryKey: Boolean, accepted: Boolean) {
        visited++
        if (!isVisible) return
        visible++
        val type = className.take(100)
        if (type in classes || classes.size < 12) classes[type] = (classes[type] ?: 0) + 1
        if (canClick) clickable++
        if (textEntryKey) textEntry++
        if (accepted) recognized++
        if (!enabled || candidates.size >= 40) return
        val semantic = KeyboardKeyPolicy.identify(text, description, className, true, true) ?: return
        candidates.add(buildJsonObject {
            put("label", semantic.label); put("class", className.take(100))
            put("clickable", canClick); put("text_entry_key", textEntryKey); put("recognized", accepted)
        })
    }
    fun json() = buildJsonObject {
        put("visited", visited); put("visible", visible); put("clickable", clickable)
        put("text_entry_keys", textEntry); put("recognized_keys", recognized)
        put("classes", buildJsonObject { classes.forEach { (name, count) -> put(name, count) } })
        put("diagnostic_only", true); put("candidates", JsonArray(candidates))
    }
}
