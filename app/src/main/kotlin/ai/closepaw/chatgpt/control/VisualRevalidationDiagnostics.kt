package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Only comparison outcomes are exported; no pixels, hashes, identifiers or geometry.
 * False means values compare equal, not that sentinel or unknown values were verified. */
internal object VisualRevalidationDiagnostics {
    fun metrics(expected: VisualTargetProof, fresh: VisualTargetProof) = buildJsonObject {
        put("pixels_changed", fresh.pixelHash != expected.pixelHash)
        put("window_bounds_changed", fresh.window != expected.window)
        put("context_changed", fresh.context != expected.context)
        put("source_window_changed", fresh.sourceWindowId != expected.sourceWindowId)
        put("scope_changed", fresh.scope != expected.scope)
        put("source_display_changed", fresh.sourceDisplayId != expected.sourceDisplayId)
        put("rotation_changed", fresh.rotation != expected.rotation)
        put("disclosed_regions_changed", fresh.disclosedRegions != expected.disclosedRegions)
        put("editor_privacy_generation_changed", fresh.editorPrivacyGeneration != expected.editorPrivacyGeneration)
    }
}
