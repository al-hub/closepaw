package ai.closepaw.chatgpt

import ai.closepaw.chatgpt.operations.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Read-only observation of an already verified foreground. No launch or action capability is injected. */
internal class GeminiUiInspectionOperation(private val inspect: suspend () -> JsonObject) : OperationHandler {
    companion object {
        fun descriptor(readiness: () -> OperationReadiness = { OperationReadiness() }) = OperationDescriptor(
            id = "gemini.ui.inspect",
            description = "Inspect bounded metadata for the current verified Gemini screen. Returns node roles, identifier/class allowlisted metadata and text lengths only. Never returns question/reply text, launches an app, writes a draft or sends. Keep Gemini foreground and unlock the device first.",
            inputSchema = buildJsonObject {
                put("type","object"); put("additionalProperties",false); put("properties",buildJsonObject {})
            },
            readOnly = true, permissions = listOf("accessibility"), effects = emptyList(), readiness = readiness,
        )
    }
    override suspend fun invoke(invocation: OperationInvocation): OperationResult {
        if (invocation.input.isNotEmpty()) return OperationResult(success=false,summary="Inspection accepts an empty input object only.")
        return try {
            val metadata = inspect()
            val verified = metadata["foreground"]?.jsonPrimitive?.booleanOrNull == true
            OperationResult(data=metadata,success=verified,summary=if(verified)
                "Current Gemini control metadata collected. No UI action was invoked."
                else "Current Gemini screen could not be verified. No UI action was invoked.")
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { OperationResult(success=false,summary="Gemini metadata inspection failed. No UI action was invoked.") }
    }
}
