package ai.closepaw.bridge

import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.AndroidPlatform

/** Exposes Android app launching through the AA-Bridge ANDROID_INTENT capability. */
class AndroidIntentExecutionAdapter(
    private val platform: AndroidPlatform,
) : ExecutionAdapter {
    override val id: String = "android-intent"
    override val capabilities: Set<ExecutionCapability> = setOf(ExecutionCapability.ANDROID_INTENT)

    override suspend fun probe(): AdapterAvailability =
        if (platform.hasRequiredPermissions()) AdapterAvailability.Available
        else AdapterAvailability.NeedsSetup("Android platform permissions are not ready")

    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        require(request.capability == ExecutionCapability.ANDROID_INTENT) {
            "Android Intent adapter only provides ANDROID_INTENT"
        }
        val packageName = request.command.trim()
        if (packageName.isEmpty()) {
            return ExecutionResult(id, 2, "", "Package name is required")
        }
        return when (val result = platform.launchApp(packageName)) {
            is ActionResult.Success -> ExecutionResult(id, 0, result.message, "")
            is ActionResult.Failure -> ExecutionResult(id, 1, "", result.reason)
            else -> ExecutionResult(id, 1, "", "Unexpected Android intent result")
        }
    }
}
