package ai.closepaw.chatgpt

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ClosePawStatusTool(
    private val versionName: String,
    private val versionCode: Int,
) {
    fun snapshot(): JsonObject = buildJsonObject {
        put("status", "succeeded")
        put("summary", "ClosePaw is reachable and responding.")
        put("app", "ClosePaw")
        put("version_name", versionName)
        put("version_code", versionCode)
        put("capability", "status")
    }
}
