package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.operations.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal class ControlSessionOperations(
    private val session: () -> ControlSession?,
    private val invalidate: () -> Unit,
    private val hintEnvironment: () -> HintEnvironment? = { null },
    private val hintDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val readiness: () -> OperationReadiness,
) {
    data class HintEnvironment(val sharing: Boolean, val nowMs: Long)

    fun register(registry: OperationRegistry) {
        registerUserAction(registry)
        for (command in listOf("status", "start", "wait", "end")) {
            val properties = if (command in setOf("wait", "end")) buildJsonObject {
                put("session_id", buildJsonObject { put("type", "string"); put("maxLength", 128); put("minLength", 1) })
                if (command == "wait") put("user_action", buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray(ControlSession.UserAction.entries.map { JsonPrimitive(it.id) }))
                    put("description", "Optional on-device instruction only; does not grant, revoke or resume control.")
                })
            } else buildJsonObject {}
            registry.register(OperationDescriptor("android.control.session.$command",
                when (command) {
                    "start" -> "Explicitly start a NEW user-authorized UI control session from idle/ended. Never use to override a user pause or emergency stop. Returns session_id required on subsequent UI operations."
                    "wait" -> "Hand control to the user after asking for a physical selection/confirmation. Optionally show a fixed one-line instruction for screen sharing. Does not change the existing handoff or resume policy."
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
                        "wait" -> {
                            val raw = invocation.input["user_action"]
                            val action = if (raw == null) null else {
                                ControlSession.UserAction.entries.firstOrNull { raw == JsonPrimitive(it.id) }
                                    ?: return OperationResult(success = false, summary = "invalid_user_action")
                            }
                            value.waitForUser(action, invocation.input["session_id"]?.jsonPrimitive?.content)
                        }
                        "end" -> { value.end(); true }
                        else -> true
                    }
                    if (command != "status") invalidate()
                    return OperationResult(success = changed, summary = if (changed) "Control session state returned." else "control_session_transition_refused",
                        data = buildJsonObject {
                            put("state", value.state.name.lowercase()); put("session_id", value.id); put("epoch", value.epoch)
                            put("user_action_revision", value.userActionRevision)
                            put("physical_resume_required", value.state == ControlSession.State.PAUSED)
                            put("external_effects_undone", false)
                        })
                }
            })
        }
    }

    private fun registerUserAction(registry: OperationRegistry) {
        val fields = listOf("session_id", "revision", "action")
        registry.register(OperationDescriptor(USER_ACTION,
            "Show, replace, or clear a one-line user-action prompt (and subtle wave) on the ClosePaw island. " +
                "Use only when the user must physically allow or stop screen sharing. Requires the current explicit active session. " +
                "Use a strictly increasing revision starting at 1 for each new request or clear; read session.status for the last revision. " +
                "The same revision is idempotent. Repeating a pending action does not extend its 30s lifetime. " +
                "The local sharing state clears the prompt automatically. This operation cannot change control, start or stop sharing, or grant consent.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("session_id", buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 128) })
                    put("revision", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", Int.MAX_VALUE) })
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray((ControlSession.UserAction.entries.map { it.id } + "clear").map { JsonPrimitive(it) }))
                    })
                })
                put("required", JsonArray(fields.map { JsonPrimitive(it) }))
                put("additionalProperties", false)
            },
            readOnly = false, permissions = listOf("accessibility", "generic_android_control"),
            effects = listOf("island_user_action_hint"), readiness = readiness,
        ), object : OperationHandler {
            override suspend fun invoke(invocation: OperationInvocation): OperationResult = withContext(hintDispatcher) {
                if (!readiness().ready) return@withContext OperationResult(success = false, summary = "generic_control_unavailable")
                val current = session() ?: return@withContext OperationResult(success = false, summary = "accessibility_permission_missing")
                val epoch = invocation.admittedEpoch
                    ?: return@withContext OperationResult(success = false, summary = "user_action_epoch_required")
                val sessionId = (invocation.input["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return@withContext OperationResult(success = false, summary = "control_session_id_required")
                val revision = (invocation.input["revision"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                if (revision == null || revision < 1)
                    return@withContext OperationResult(success = false, summary = "invalid_user_action_revision")
                val raw = invocation.input["action"]
                val action = if (raw == JsonPrimitive("clear")) null else {
                    ControlSession.UserAction.entries.firstOrNull { raw == JsonPrimitive(it.id) }
                        ?: return@withContext OperationResult(success = false, summary = "invalid_user_action")
                }
                val environment = hintEnvironment()
                    ?: return@withContext OperationResult(success = false, summary = "device_locked_or_unavailable")
                val result = current.updateUserAction(sessionId, epoch, revision, action, environment.sharing, environment.nowMs)
                OperationResult(
                    success = result !in setOf(ControlSession.HintUpdate.STALE, ControlSession.HintUpdate.REFUSED),
                    summary = "island_user_action_${result.name.lowercase()}",
                    data = buildJsonObject { put("user_action_revision", current.userActionRevision) },
                )
            }
        })
    }

    companion object {
        const val USER_ACTION = "android.control.island.user_action"
    }
}
