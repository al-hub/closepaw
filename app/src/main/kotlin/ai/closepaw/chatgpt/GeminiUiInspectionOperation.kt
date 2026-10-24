package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.operations.*
import ai.closepaw.chatgpt.control.ControlSession
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Read-only observation of an already verified foreground. No launch or action capability is injected. */
internal class GeminiUiInspectionOperation(private val inspect: suspend (String) -> JsonObject) : OperationHandler {
    companion object {
        fun descriptor(readiness: () -> OperationReadiness = { OperationReadiness() }) = OperationDescriptor(
            id = "gemini.ui.inspect",
            description = "Inspect bounded metadata for the current verified Gemini screen. Returns node roles, identifier/class allowlisted metadata and text lengths only. Never returns question/reply text, launches an app, writes a draft or sends. Requires session_id from an explicitly active control session. Never starts or resumes a session. Keep Gemini foreground and unlock the device first.",
            inputSchema = buildJsonObject {
                put("type","object"); put("additionalProperties",false)
                put("properties",buildJsonObject {
                    put("session_id",buildJsonObject { put("type","string"); put("minLength",1); put("maxLength",128) })
                })
                put("required",buildJsonArray { add("session_id") })
            },
            readOnly = true, permissions = listOf("accessibility"), effects = emptyList(), readiness = readiness,
        )
        /** Pure admission check shared by the Android runtime and regression tests. Never mutates a session. */
        fun sessionRefusal(session: ControlSession?, sessionId: String): String? = when {
            session == null -> "accessibility_permission_missing"
            !session.explicit || session.state != ControlSession.State.ACTIVE -> "explicit_active_session_required"
            else -> session.refusal(sessionId)
        }
    }
    override suspend fun invoke(invocation: OperationInvocation): OperationResult {
        if (OperationSchemaValidator.validate(descriptor().inputSchema, invocation.input) != null)
            return OperationResult(success=false,summary="Inspection requires only a valid session_id.")
        return try {
            val metadata = inspect(invocation.input.getValue("session_id").jsonPrimitive.content)
            val verified = metadata["foreground"]?.jsonPrimitive?.booleanOrNull == true
            OperationResult(data=metadata,success=verified,summary=if(verified)
                "Current Gemini control metadata collected. No UI action was invoked."
                else "Current Gemini screen could not be verified. No UI action was invoked.")
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            val reason = when (error) {
                is SecurityException -> "snapshot_security_exception"
                is IllegalArgumentException -> "snapshot_invalid_argument"
                is IllegalStateException -> "snapshot_illegal_state"
                else -> "snapshot_failed"
            }
            OperationResult(success=false,summary="Gemini metadata inspection failed. No UI action was invoked.",
                data=buildJsonObject { put("foreground", false); put("reason_code", reason) })
        }
    }
}
