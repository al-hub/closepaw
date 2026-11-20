package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.*

/** Private comparison inputs. Never serialize these fingerprints to the task result. */
internal data class KeyboardVisualFingerprintParts(
    val context: String, val appTree: String, val imeTree: String?, val chrome: ChromeTreeRegions.Fingerprints? = null,
)

internal object KeyboardVisualPostCheck {
    /** A keyboard-only image masks Chrome pixels. A caption update alone may
     * occur during its capture. This never authorizes a touch and never makes
     * an exception for structure, editor values, any window or IME changes. */
    fun suggestionCaptionOnly(before: ControlFrame, after: ControlFrame): Boolean {
        if (before.packageName != LocalImeGeometryFixture.CHROME ||
            before.keyboard?.packageName != LocalImeGeometryFixture.SAMSUNG ||
            before.inputTarget?.resourceId != "com.android.chrome:id/url_bar" ||
            before.inputTarget != after.inputTarget ||
            !KeyboardEditorContinuity.sameFocusedEditor(before, after, allowAnonymousResize = false) ||
            !KeyboardEditorContinuity.sameValue(before, after) ||
            GenericControlActionPolicy.refusal(before, after.copy(fingerprint = before.fingerprint), "visual_key") != null)
            return false
        val old = before.keyboardVisualFingerprintParts ?: return false
        val new = after.keyboardVisualFingerprintParts ?: return false
        val a = old.chrome ?: return false
        val b = new.chrome ?: return false
        return old.context == new.context && old.appTree != new.appTree &&
            old.imeTree != null && old.imeTree == new.imeTree &&
            a.editor != null && a.editor == b.editor && a.other != null && a.other == b.other &&
            a.suggestions != null && b.suggestions != null && a.suggestions != b.suggestions &&
            a.suggestionShape != null && a.suggestionShape == b.suggestionShape
    }

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
