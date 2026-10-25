package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.operations.*
import ai.closepaw.termux.*
import java.util.Locale
import kotlinx.serialization.json.*

/** Offline-safe diagnostics. Does not use the recovery gate or submit an execution request. */
internal class TermuxBridgeStatusOperation(
    private val observe: suspend () -> TermuxBridgeDiagnosticSnapshot,
) : OperationHandler {
    override suspend fun invoke(invocation: OperationInvocation): OperationResult {
        val snapshot = observe()
        val action = when {
            snapshot.install == TermuxInstallState.NotInstalled -> "install_termux"
            snapshot.health == HealthProbe.AuthMismatch -> "inspect_pairing_without_rotating_token"
            snapshot.health == HealthProbe.InvalidIdentity -> "inspect_port_owner_do_not_kill"
            snapshot.health == HealthProbe.BridgeOutdated -> "explicit_bridge_upgrade_required"
            snapshot.health == HealthProbe.Ready -> "none"
            snapshot.health == HealthProbe.UnverifiedListener -> "inspect_unverified_listener_do_not_change_pairing"
            snapshot.health == HealthProbe.ResponseTimeout -> "inspect_unresponsive_bridge_do_not_replay"
            snapshot.install == TermuxInstallState.RunCommandUnavailable -> "run_existing_recovery_hook_once_in_termux"
            else -> "bounded_service_recovery_available_subject_to_android_policy"
        }
        return OperationResult(summary = "Passive Local Bridge observations; process and supervisor state are not observed.",
            data = buildJsonObject {
                put("termux_installed", snapshot.install != TermuxInstallState.NotInstalled)
                put("run_command_supported", snapshot.install == TermuxInstallState.Available)
                put("run_command_check", "installed_package_permission_action_exported_service")
                put("health_status", snapshot.health.name.lowercase(Locale.ROOT))
                put("authenticated_jobs_ready", snapshot.health == HealthProbe.Ready)
                put("process_state", "not_observed")
                put("supervisor_state", "not_observed")
                put("recommended_action", action)
                put("recovery_attempted", false)
                put("command_submitted", false)
                put("pairing_changed", false)
            })
    }

    companion object {
        fun descriptor(enabled: () -> Boolean) = OperationDescriptor(
            id = "linux.bridge.status",
            description = "Read passive Local Bridge health, authenticated jobs readiness and installed Termux RUN_COMMAND service support, including while offline. No command, recovery, token changes or UI session. Process and supervisor state remain unobserved.",
            inputSchema = buildJsonObject {
                put("type", "object"); put("properties", buildJsonObject {})
                put("additionalProperties", false); put("required", buildJsonArray {})
            },
            permissions = listOf("remote_execution", "termux_local_bridge"),
            readiness = { OperationReadiness(enabled(), if (enabled()) null else "remote_execution_disabled") },
        )
    }
}
