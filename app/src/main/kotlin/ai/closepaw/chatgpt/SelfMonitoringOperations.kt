package ai.closepaw.chatgpt

import ai.closepaw.app.AgentService
import ai.closepaw.chatgpt.control.ControlSession
import ai.closepaw.chatgpt.operations.*
import ai.closepaw.termux.*
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal class SelfMonitoringOperations(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: McpExecutionSettings,
    private val bridge: TermuxBridgeManager,
) {
    private fun state(session: ControlSession, id: String, epoch: Long) = MonitoringRecoveryGate.State(
        SystemClock.elapsedRealtime(),
        scope.isActive && settings.enabled && settings.genericControlEnabled &&
            AgentService.instance?.remoteControlSession === session && session.id == id && session.epoch == epoch &&
            session.state == ControlSession.State.ACTIVE,
        !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
            context.getSystemService(android.os.PowerManager::class.java).isInteractive,
    )
    private fun schema(start: Boolean) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        put("properties", buildJsonObject {
            if (start) {
                put("session_id", buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 128) })
                put("duration_seconds", buildJsonObject { put("type", "integer"); put("minimum", 10); put("maximum", 300) })
                put("recovery", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf(JsonPrimitive(SelfMonitoringAuthority.ACTION)))) })
            }
        })
        put("required", JsonArray(if (start) listOf("session_id", "duration_seconds", "recovery").map(::JsonPrimitive) else emptyList()))
    }
    fun register(registry: OperationRegistry) {
        registry.register(OperationDescriptor(START,
            "Request device approval to monitor the local bridge for 10–300 seconds and start the existing offline bridge at most once. No shell input, install, sharing consent or permission changes. Does not start until the user taps Approve on the phone.",
            inputSchema = schema(true), readOnly = false,
            permissions = listOf("generic_android_control", "remote_execution", "termux_local_bridge"),
            effects = listOf("device_consent_dialog", "bounded_bridge_recovery"),
            readiness = { OperationReadiness(settings.enabled && settings.genericControlEnabled && AgentService.instance != null) },
        ), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation): OperationResult {
                val session = AgentService.instance?.remoteControlSession ?: return OperationResult(success = false, summary = "Accessibility service unavailable.")
                val sessionId = invocation.input.getValue("session_id").jsonPrimitive.content
                val epoch = session.epoch
                if (session.id != sessionId || !state(session, sessionId, epoch).let { it.active && it.unlocked })
                    return OperationResult(success = false, summary = "An active unlocked control session is required.")
                val id = invocation.taskId
                if (!SelfMonitoringAuthority.request(id, sessionId, epoch,
                        invocation.input.getValue("duration_seconds").jsonPrimitive.long * 1000, SystemClock.elapsedRealtime()))
                    return OperationResult(success = false, summary = "An approval or monitoring session is already active.")
                try {
                    withContext(Dispatchers.Main) {
                        context.startActivity(Intent(context, SelfMonitoringConsentActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("monitor_id", id))
                    }
                } catch (_: Exception) {
                    SelfMonitoringAuthority.stop(id, "consent_ui_unavailable")
                    return OperationResult(success = false, summary = "Open ClosePaw before requesting monitoring approval.")
                }
                scope.launch {
                    SelfMonitoringRunner(
                        state = { state(session, sessionId, epoch) },
                        probe = {
                            val observation = bridge.diagnosticSnapshot()
                            when {
                                observation.health == HealthProbe.Ready -> "ready"
                                observation.health == HealthProbe.Unavailable && observation.install == TermuxInstallState.Available -> "offline_recoverable"
                                else -> "blocked"
                            }
                        },
                        recover = { guard -> bridge.recoverForMonitoring(guard) },
                    ).run(id)
                }
                return OperationResult(summary = "Device approval requested; monitoring has not been approved.",
                    data = buildJsonObject { put("monitor_id", id); put("state", "awaiting_consent"); put("recovery_attempted", false) })
            }
        })
        registry.register(OperationDescriptor(STATUS, "Read bounded monitoring state and independent post-recovery health. No recovery or approval.", inputSchema = schema(false)),
            object : OperationHandler {
                override suspend fun invoke(invocation: OperationInvocation): OperationResult {
                    val grant = SelfMonitoringAuthority.snapshot()
                    return OperationResult(summary = "Monitoring authority snapshot; no action performed.", data = buildJsonObject {
                        put("state", grant?.phase ?: "idle"); put("recovery_attempted", grant?.dispatched ?: false)
                        put("post_check", grant?.postCheck ?: "not_observed")
                        grant?.let { put("monitor_id", it.id); put("recovery", SelfMonitoringAuthority.ACTION) }
                    })
                }
            })
        registry.register(OperationDescriptor(STOP, "Stop monitoring and revoke unused recovery authority. An already dispatched external effect cannot be undone.",
            inputSchema = schema(false), readOnly = false, effects = listOf("monitor_stop")),
            object : OperationHandler {
                override suspend fun invoke(invocation: OperationInvocation): OperationResult {
                    SelfMonitoringAuthority.stop()
                    return OperationResult(summary = "Monitoring stopped; no new recovery dispatch can begin.")
                }
            })
    }
    companion object {
        const val START = "closepaw.monitor.start"
        const val STATUS = "closepaw.monitor.status"
        const val STOP = "closepaw.monitor.stop"
    }
}
