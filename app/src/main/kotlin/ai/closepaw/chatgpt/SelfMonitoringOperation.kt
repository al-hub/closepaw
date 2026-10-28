package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.operations.*
import kotlinx.serialization.json.*

/** A passive, point-in-time observation. It never starts or repairs a service. */
internal class SelfMonitoringOperation(
    private val local: () -> LocalState,
) : OperationHandler {
    internal data class LocalState(
        val accessibilityConnected: Boolean,
        val sessionState: String?,
        val deviceLocked: Boolean,
        val controlEnabled: Boolean,
        val remoteExecutionEnabled: Boolean,
        val projectionLeaseActive: Boolean,
    )

    override suspend fun invoke(invocation: OperationInvocation): OperationResult {
        val state = local()
        return OperationResult(summary = "Passive point-in-time observations; no recovery was attempted.",
            data = buildJsonObject {
                put("connection", "request_received")
                put("accessibility_service", if (state.accessibilityConnected) "connected" else "not_connected")
                put("control_session", state.sessionState ?: "not_observed")
                put("device_locked", state.deviceLocked)
                put("generic_control_enabled", state.controlEnabled)
                put("remote_execution_enabled", state.remoteExecutionEnabled)
                put("projection_lease", if (state.projectionLeaseActive) "active" else "not_active")
                put("bridge_health", "not_observed")
                put("bridge_process", "not_observed")
                put("recent_error", "not_observed")
                put("recovery_attempted", false)
                put("scope", "point_in_time_not_end_to_end")
            })
    }

    companion object {
        fun descriptor(enabled: () -> Boolean) = OperationDescriptor(
            id = "closepaw.self.status",
            description = "Read passive ClosePaw service, control and projection lease observations. For bridge health, use linux.bridge.status subject to its readiness rules. Never starts or repairs services. A response proves only that this request was received.",
            inputSchema = buildJsonObject {
                put("type", "object"); put("properties", buildJsonObject {})
                put("additionalProperties", false); put("required", buildJsonArray {})
            },
            permissions = listOf("generic_android_control"),
            readiness = { OperationReadiness(enabled(), if (enabled()) null else "generic_control_disabled") },
        )
    }
}
