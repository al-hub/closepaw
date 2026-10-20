package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.operations.*
import kotlinx.serialization.json.*

internal class ControlSessionOperations(private val session: () -> ControlSession?,
    private val invalidate: () -> Unit, private val readiness: () -> OperationReadiness) {
    fun register(registry: OperationRegistry) {
        for (command in listOf("status", "start", "wait", "end")) {
            val properties = if (command in setOf("wait", "end")) buildJsonObject {
                put("session_id", buildJsonObject { put("type", "string"); put("maxLength", 128); put("minLength", 1) })
            } else buildJsonObject {}
            registry.register(OperationDescriptor("android.control.session.$command",
                when (command) {
                    "start" -> "Explicitly start a NEW user-authorized UI control session from idle/ended. Never use to override a user pause or emergency stop. Returns session_id required on subsequent UI operations."
                    "wait" -> "Hand control to the user after asking for a physical selection/confirmation. Shows breathing border; AI actions stay blocked until the user confirms on-device."
                    "end" -> "End this UI control session and cancel cancellable work. Completed external effects are not undone. No resume; a new explicitly authorized session is required."
                    else -> "Read control session state and identity. Paused control can only resume through the physical on-device play button."
                }, inputSchema = buildJsonObject {
                    put("type", "object"); put("properties", properties); put("additionalProperties", false)
                    put("required", JsonArray(if (command in setOf("wait", "end")) listOf(JsonPrimitive("session_id")) else emptyList()))
                }, readOnly = command == "status", permissions = listOf("accessibility", "generic_android_control"),
                effects = if (command == "status") emptyList() else listOf("control_session_state"), readiness = readiness), object : OperationHandler {
                override suspend fun invoke(invocation: OperationInvocation): OperationResult {
                    if (!readiness().ready) return OperationResult(success = false, summary = "generic_control_unavailable")
                    val value = session() ?: return OperationResult(success = false, summary = "accessibility_permission_missing")
                    if (command in setOf("wait", "end") && invocation.input["session_id"]?.jsonPrimitive?.content != value.id)
                        return OperationResult(success = false, summary = "control_session_id_required")
                    val changed = when (command) {
                        "start" -> value.start()
                        "wait" -> value.waitForUser()
                        "end" -> { value.end(); true }
                        else -> true
                    }
                    if (command != "status") invalidate()
                    return OperationResult(success = changed, summary = if (changed) "Control session state returned." else "control_session_transition_refused",
                        data = buildJsonObject {
                            put("state", value.state.name.lowercase()); put("session_id", value.id); put("epoch", value.epoch)
                            put("physical_resume_required", value.state == ControlSession.State.PAUSED)
                            put("external_effects_undone", false)
                        })
                }
            })
        }
    }
}
