package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val VISUAL_SOURCE_REFUSED = "visual_source_revoked_or_expired"

internal enum class VisualSourceCondition(val code: String) {
    IMAGE_CONSENT_CHANGED("image_consent_changed"),
    DISPLAY_WINDOW_CHANGED("display_window_changed"),
    IMAGE_SOURCE_UNAVAILABLE("image_source_unavailable"),
    CONTROL_SESSION_CHANGED("control_session_changed"),
}

internal enum class VisualSourceStage(val code: String) {
    ACTION_VALIDATION("action_validation"),
    NATIVE_ACTION_VALIDATION("native_action_validation"),
    PRE_GESTURE("pre_gesture"),
}

/** Bounded codes only. No screenshot, input, window identifier or generation is exported. */
internal data class VisualSourceFailure(
    val stage: VisualSourceStage, val condition: VisualSourceCondition, val windowEvents: JsonObject? = null,
) {
    fun json() = buildJsonObject {
        put("stage", stage.code); put("condition", condition.code)
        windowEvents?.let { put("window_event_interval", it) }
    }
}

/** The decision and its diagnostic come from one ordered, short-circuit check. */
internal class VisualSourceGate(
    private val consentValid: () -> Boolean,
    private val displayValid: () -> Boolean,
    private val sourceValid: () -> Boolean,
    private val sessionValid: () -> Boolean,
) {
    fun refusal(): VisualSourceCondition? = when {
        !consentValid() -> VisualSourceCondition.IMAGE_CONSENT_CHANGED
        !displayValid() -> VisualSourceCondition.DISPLAY_WINDOW_CHANGED
        !sourceValid() -> VisualSourceCondition.IMAGE_SOURCE_UNAVAILABLE
        !sessionValid() -> VisualSourceCondition.CONTROL_SESSION_CHANGED
        else -> null
    }

    fun allowed(): Boolean = refusal() == null
}
