package ai.closepaw.bridge

import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.platform.SystemButtonType
import ai.closepaw.platform.UIAction
import org.json.JSONObject

/**
 * Exposes a small, transport-neutral Accessibility action surface through AA-Bridge.
 *
 * Commands are JSON so callers select the ACCESSIBILITY capability, not an Android product.
 */
class AccessibilityExecutionAdapter(
    private val platform: AndroidPlatform,
) : ExecutionAdapter {
    override val id: String = "android-accessibility"
    override val capabilities: Set<ExecutionCapability> = setOf(ExecutionCapability.ACCESSIBILITY)

    override suspend fun probe(): AdapterAvailability =
        if (platform.hasRequiredPermissions()) AdapterAvailability.Available
        else AdapterAvailability.NeedsSetup("Accessibility service is not ready")

    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        require(request.capability == ExecutionCapability.ACCESSIBILITY) {
            "Accessibility adapter only provides ACCESSIBILITY"
        }
        val action = try {
            parseAction(JSONObject(request.command))
        } catch (error: Exception) {
            return ExecutionResult(id, 2, "", "Invalid accessibility command: ${error.message}")
        }
        return when (val result = platform.performAction(action)) {
            is ActionResult.Success -> ExecutionResult(id, 0, result.message, "")
            is ActionResult.Failure -> ExecutionResult(id, 1, "", result.reason)
            else -> ExecutionResult(id, 1, "", "Unexpected accessibility result")
        }
    }

    private fun parseAction(json: JSONObject): UIAction =
        when (json.getString("action")) {
            "tap" -> UIAction.TapAt(json.getInt("x"), json.getInt("y"))
            "long_press" -> UIAction.LongPressAt(
                json.getInt("x"),
                json.getInt("y"),
                json.optLong("duration_ms", 600L),
            )
            "swipe" -> UIAction.Swipe(
                json.getInt("start_x"),
                json.getInt("start_y"),
                json.getInt("end_x"),
                json.getInt("end_y"),
                json.optLong("duration_ms", 300L),
            )
            "back" -> UIAction.SystemButton(SystemButtonType.BACK)
            "home" -> UIAction.SystemButton(SystemButtonType.HOME)
            "recents" -> UIAction.SystemButton(SystemButtonType.RECENTS)
            "wait" -> UIAction.Wait(json.getLong("duration_ms"))
            else -> error("unsupported action")
        }
}
