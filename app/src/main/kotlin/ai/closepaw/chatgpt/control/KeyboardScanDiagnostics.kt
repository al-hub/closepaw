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
    // Counts only: not native touch authority and never inferred from geometry.
    private var caseSwitchSemanticNodes = 0
    private var caseSwitchNativeRecognized = 0
    private val classes = linkedMapOf<String, Int>()
    private val candidates = mutableListOf<JsonObject>()
    private var omittedCandidates = 0
    // Fixed, non-sensitive #147 roles; sightings never establish touch authority.
    private data class Watched(
        var seen: Int = 0, var visible: Int = 0, var enabled: Int = 0,
        var disabled: Int = 0, var clickable: Int = 0, var textEntry: Int = 0,
        var nativeRecognized: Int = 0, var nonEntry: Int = 0,
    )
    private val watched = linkedMapOf("ㄱ" to Watched(), "ㅏ" to Watched(),
        "ㄴ" to Watched(), "Space" to Watched())
    fun record(text: String, description: String, className: String, isVisible: Boolean,
        enabled: Boolean, canClick: Boolean, textEntryKey: Boolean, accepted: Boolean) {
        visited++
        val semantic = KeyboardKeyPolicy.identify(text, description, className, true, true)
        semantic?.label?.let { watched[it] }?.let { stat ->
            stat.seen++
            if (isVisible) {
                stat.visible++
                if (enabled) stat.enabled++ else stat.disabled++
                if (canClick) stat.clickable++
                if (textEntryKey) stat.textEntry++ else stat.nonEntry++
                if (accepted && enabled) stat.nativeRecognized++
            }
        }
        if (!isVisible) return
        visible++
        val type = className.take(100)
        if (type in classes || classes.size < 12) classes[type] = (classes[type] ?: 0) + 1
        if (canClick) clickable++
        if (textEntryKey) textEntry++
        if (accepted) recognized++
        if (!enabled) return
        val recognizedSemantic = semantic ?: return
        // Count allowlisted case-switch labels across the FULL traversal. The
        // presented candidate list is capped at 40, often before the bottom row.
        // No role is inferred from a missing/anonymous native label.
        if (recognizedSemantic.kind == KeyboardKeyPolicy.Kind.LAYOUT &&
            recognizedSemantic.label.lowercase() in setOf("shift", "caps lock", "⇧", "⇪")) {
            caseSwitchSemanticNodes++
            if (accepted) caseSwitchNativeRecognized++
        }
        if (candidates.size >= 40) { omittedCandidates++; return }
        candidates.add(buildJsonObject {
            put("label", recognizedSemantic.label); put("class", className.take(100))
            put("clickable", canClick); put("text_entry_key", textEntryKey); put("recognized", accepted)
        })
    }
    fun json() = buildJsonObject {
        put("visited", visited); put("visible", visible); put("clickable", clickable)
        put("text_entry_keys", textEntry); put("recognized_keys", recognized)
        put("case_switch_semantic_nodes", caseSwitchSemanticNodes)
        put("case_switch_native_recognized", caseSwitchNativeRecognized)
        put("classes", buildJsonObject { classes.forEach { (name, count) -> put(name, count) } })
        put("diagnostic_only", true)
        put("candidate_list_limit", 40)
        put("candidate_list_omitted", omittedCandidates)
        put("tracked_key_counts", buildJsonObject {
            watched.forEach { (label, stat) ->
                put(label, buildJsonObject {
                    put("semantic_seen", stat.seen); put("visible", stat.visible)
                    put("enabled", stat.enabled); put("disabled", stat.disabled)
                    put("clickable", stat.clickable); put("text_entry", stat.textEntry)
                    put("non_entry", stat.nonEntry)
                    put("native_recognized", stat.nativeRecognized)
                    put("diagnostic_only", true)
                })
            }
        })
        put("candidates", JsonArray(candidates))
    }
}
