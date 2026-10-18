package ai.closepaw.update

import ai.closepaw.chatgpt.operations.*
import kotlinx.serialization.json.*

internal class SelfUpdateOperations(private val updater: AndroidSelfUpdater) {
    fun register(registry: OperationRegistry) {
        val empty = buildJsonObject {
            put("type", "object"); put("properties", buildJsonObject {}); put("additionalProperties", false)
        }
        registry.register(OperationDescriptor(id = STATUS, inputSchema = empty,
            description = "Read durable self-update state and current installed version. installed confirms version/code only; a successful query is not proof of installation or tunnel health. After reconnect, query this before any retry. Security/install approvals may require the user."), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation) = result(updater.status())
        })
        registry.register(OperationDescriptor(id = DIAGNOSTICS, inputSchema = empty,
            description = "Read app-scoped device/install metadata and bounded remote installer callbacks. Does not install, launch screens, or change security settings. Null/unknown fields are not evidence of absence. Confirmation handler is not proof of warning ownership; no silent-install guarantee."), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation) = OperationResult(
                data = updater.diagnostics(), summary = "Update diagnostics captured. Security settings and warning ownership may remain unknown; unattended installation is not verified.")
        })
        registry.register(OperationDescriptor(id = CHECK, inputSchema = empty,
            effects = listOf("github_release_read"), description = "Check the latest signed GitHub release. Use the returned available_version as expected_version for self-update. Does not download or install."), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation): OperationResult = when(val checked = AppUpdater.check()) {
                AppUpdater.CheckResult.UpToDate -> result(buildJsonObject { put("state", "up_to_date") })
                is AppUpdater.CheckResult.Failed -> OperationResult(success = false, summary = "Release check failed.")
                is AppUpdater.CheckResult.Available -> result(buildJsonObject { put("state", "available"); put("available_version", checked.release.version) })
            }
        })
        registry.register(OperationDescriptor(id = INSTALL, readOnly = false,
            description = "Download the expected latest ClosePaw release, verify SHA-256/package/version/signing identity, then request Android self-update. Requires local remote-update and install-source permissions. Connection can drop during replacement. Installing/accepted is not installed. After reconnect query app.self_update.status; do not replay unresolved updates. Cannot bypass Play Protect or Samsung security approvals.",
            inputSchema = buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("properties", buildJsonObject { put("expected_version", buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 64) }) })
                put("required", buildJsonArray { add("expected_version") })
            }, permissions = listOf("remote_self_update", "install_packages"),
            effects = listOf("apk_download", "package_self_update", "connection_restart"),
            readiness = { OperationReadiness(updater.enabled(), if (updater.enabled()) null else "self_update_disabled") }), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation) = result(updater.start(invocation.taskId,
                invocation.input.getValue("expected_version").jsonPrimitive.content))
        })
    }
    private fun result(data: JsonObject) = OperationResult(data = data, success = data["state"]?.jsonPrimitive?.content !in setOf("failed", "install_outcome_unknown", "interrupted_before_install"), summary =
        "Self-update state: ${data["state"]?.jsonPrimitive?.content}. Check installed version after reconnection; handle required approval with the user.")
    companion object {
        const val DIAGNOSTICS = "app.self_update.diagnostics"
        const val CHECK = "app.self_update.check"
        const val STATUS = "app.self_update.status"
        const val INSTALL = "app.self_update.install"
    }
}
