package ai.closepaw.update

import kotlinx.serialization.json.*

/** Only bounded routing metadata; no Intent extras, URIs, raw status messages, or screen text. */
internal object InstallerCallbackEvidence {
    fun build(status: Int, relatedPackage: String?, action: String?, component: String?,
        resolvedPackage: String?, observedAt: Long): JsonObject = buildJsonObject {
        put("status", status)
        put("observed_at_ms", observedAt)
        fun metadata(key: String, value: String?) {
            if (value != null && value.length <= 256 && value.matches(Regex("[A-Za-z0-9_.$/]+"))) put(key, value)
        }
        metadata("related_package", relatedPackage)
        metadata("confirmation_action", action)
        metadata("confirmation_component", component)
        metadata("confirmation_resolved_package", resolvedPackage)
        put("warning_owner", JsonNull)
        put("warning_owner_observation", "not_proven_by_confirmation_handler")
    }
}
