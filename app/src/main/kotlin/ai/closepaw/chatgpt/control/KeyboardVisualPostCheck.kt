package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.*

/** Private comparison inputs. Never serialize these fingerprints to the task result. */
internal data class KeyboardVisualFingerprintParts(
    val context: String, val appTree: String, val imeTree: String?, val chrome: ChromeTreeRegions.Fingerprints? = null,
)

internal object KeyboardVisualPostCheck {
    /** Make the IME tree independent of a change in the app's node count. */
    fun relativeImeNode(node: JsonObject, index: Int, parent: Int, imeBase: Int): JsonObject = JsonObject(node + mapOf(
        "id" to JsonPrimitive(index - imeBase),
        "parent" to JsonPrimitive(if (parent < 0) -1 else parent - imeBase),
    ))

    /** Describe an existing screen_changed refusal, without recording screen data or IDs. */
    fun describe(before: ControlFrame, after: ControlFrame): JsonObject = buildJsonObject {
        val appChanged = before.packageName != after.packageName
        val targetChanged = before.windowId != after.windowId
        val foregroundChanged = before.foregroundWindowId != after.foregroundWindowId
        when {
            after.reason != null -> put("source", "post_capture_frame_denied")
            appChanged || targetChanged || foregroundChanged -> {
                put("source", "window_identity")
                put("app_identity_changed", appChanged)
                put("target_window_changed", targetChanged)
                put("foreground_window_changed", foregroundChanged)
            }
            else -> {
                put("source", "full_fingerprint")
                val original = before.keyboardVisualFingerprintParts
                val current = after.keyboardVisualFingerprintParts
                if (original != null && current != null) {
                    put("frame_context_changed", original.context != current.context)
                    put("app_tree_changed", original.appTree != current.appTree)
                    put("ime_tree_changed", original.imeTree != current.imeTree)
                    val oldChrome = original.chrome
                    val newChrome = current.chrome
                    if (oldChrome != null && newChrome != null) {
                        if (oldChrome.editor != null || newChrome.editor != null)
                            put("chrome_editor_changed", oldChrome.editor != newChrome.editor)
                        if (oldChrome.suggestions != null || newChrome.suggestions != null)
                            put("chrome_suggestions_changed", oldChrome.suggestions != newChrome.suggestions)
                        put("chrome_other_changed", oldChrome.other != newChrome.other)
                    }
                }
            }
        }
    }
}
