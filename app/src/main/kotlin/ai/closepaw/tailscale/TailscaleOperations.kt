package ai.closepaw.tailscale

import ai.closepaw.chatgpt.operations.*
import kotlinx.serialization.json.*

/** Operation/schema composition only; platform observation and recovery have separate owners. */
internal class TailscaleOperations(private val recovery: TailscaleRecovery, private val enabled: () -> Boolean) {
    fun register(registry: OperationRegistry) {
        registry.register(OperationDescriptor(
            id = STATUS, description = "Diagnose Tailscale installation/control support, observed VPN/link evidence, and optionally a private target TCP port. Use after private-network/SSH failures. Does not connect, install, launch apps or execute shell commands. VPN ownership, login and approval state cannot be conclusively observed.",
            inputSchema = schema(false), effects = listOf("private_tcp_probe"),
        ), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation): OperationResult {
                val input = invocation.input
                if (input["port"] != null && input["host"] == null) return invalidTarget()
                val target = try { target(input) } catch (_: IllegalArgumentException) { return invalidTarget() }
                return result(recovery.status(target), readOnly = true)
            }
        })
        registry.register(OperationDescriptor(
            id = ENSURE, description = "For a failed private-network task, request Tailscale connection at most once and verify the specified private TCP target. Independent of Termux. Requires local Tailscale recovery permission. Never replays the original command; after success resume only safe reads or confirmed-not-executed work. Active/unidentified VPNs, installation, login and VPN approval can require user action. A broadcast alone is not success.",
            inputSchema = schema(true), readOnly = false,
            permissions = listOf("tailscale_connection_control"), effects = listOf("vpn_connection_request", "private_tcp_probe"),
            readiness = { if (enabled()) OperationReadiness() else OperationReadiness(false, "tailscale_control_disabled") },
        ), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation): OperationResult {
                val input = invocation.input
                val target = try { target(input) ?: return invalidTarget() } catch (_: IllegalArgumentException) { return invalidTarget() }
                return result(recovery.ensure(target, input["timeout_ms"]?.jsonPrimitive?.int ?: 15000), readOnly = false)
            }
        })
    }

    private fun target(input: JsonObject): PrivateTarget? = input["host"]?.jsonPrimitive?.content?.let {
        PrivateTarget(it, input["port"]?.jsonPrimitive?.int ?: 22)
    }
    private fun invalidTarget() = OperationResult(success = false, summary = "Provide a private-network hostname or IP and a valid TCP port.",
        data = buildJsonObject { put("state", "invalid_target"); put("original_command_replayed", false) })

    private fun result(outcome: TailscaleOutcome, readOnly: Boolean): OperationResult = OperationResult(
        success = readOnly || outcome.success,
        summary = when {
            outcome.success -> "Private target TCP port is reachable. Verify the original task separately; no shell command was replayed."
            outcome.state == "connection_unconfirmed" -> "Connection not confirmed. Check Tailscale login/VPN approval and target availability; these causes cannot be distinguished by this probe alone."
            else -> "Tailscale diagnosis: ${outcome.state}."
        },
        data = buildJsonObject {
            put("state", outcome.state); put("control_enabled", enabled())
            put("installed", outcome.local.installed); put("app_enabled", outcome.local.appEnabled)
            put("connect_supported", outcome.local.connectSupported); put("control_permission_granted", outcome.local.controlPermissionGranted)
            outcome.local.versionName?.let { put("version_name", it) }
            outcome.local.observationError?.let { put("observation_error", it) }
            put("vpn_present", outcome.local.vpnPresent); put("overlay_address_observed", outcome.local.overlayAddressPresent)
            put("vpn_owner", "unattributed"); put("authentication_state", "not_observable")
            put("target_reachable", outcome.probe?.let { JsonPrimitive(it.reachable) } ?: JsonNull)
            put("probe_reason", outcome.probe?.reason ?: "not_probed")
            put("verification_scope", "private_tcp_only"); put("connect_requested", outcome.connectRequested)
            put("verification_context", "closepaw_android_uid")
            put("cooldown_remaining_ms", outcome.cooldownRemainingMs); put("original_command_replayed", false)
            put("next_action", when (outcome.state) {
                "target_reachable", "target_already_reachable" -> "verify_original_task_without_unsafe_replay"
                "app_not_installed" -> "install_tailscale_with_user"
                "app_disabled" -> "enable_tailscale_with_user"
                "connect_not_supported" -> "open_or_update_tailscale_with_user"
                "control_disabled" -> "enable_local_tailscale_recovery"
                "connection_cooldown" -> "wait_for_cooldown"
                "recovery_in_progress" -> "wait_for_current_recovery"
                "target_port_refused" -> "check_target_service"
                "target_dns_failed", "target_unreachable", "target_probe_timeout", "connection_not_observed" -> when {
                    outcome.local.vpnPresent -> "inspect_active_vpn_and_target_without_switching_vpn"
                    !enabled() -> "enable_local_tailscale_recovery"
                    readOnly && outcome.probe == null -> "provide_private_target_then_ensure_connected"
                    readOnly -> "invoke_network_tailscale_ensure_connected"
                    else -> "open_tailscale_check_login_vpn_approval_and_target"
                }
                "overlay_link_observed" -> "provide_private_target_for_verification"
                "target_outside_private_network", "invalid_target" -> "provide_private_target"
                "vpn_active_target_unreachable", "vpn_conflict_or_unidentified" -> "inspect_active_vpn_and_target_without_switching_vpn"
                else -> "open_tailscale_check_login_vpn_approval_and_target"
            })
        },
    )

    private fun schema(required: Boolean) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        put("properties", buildJsonObject {
            put("host", buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 253); put("description", "Private-network DNS name or IP, without URL, credentials or shell syntax. Every resolved address must be private or tailnet-ranged.") })
            put("port", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 65535); put("default", 22) })
            if (required) put("timeout_ms", buildJsonObject { put("type", "integer"); put("minimum", 2000); put("maximum", 20000); put("default", 15000) })
        })
        put("required", JsonArray(if (required) listOf(JsonPrimitive("host")) else emptyList()))
    }

    companion object {
        const val STATUS = "network.tailscale.status"
        const val ENSURE = "network.tailscale.ensure_connected"
    }
}
