package ai.closepaw.tool.impl

import ai.closepaw.platform.VerifiedInputRequest
import ai.closepaw.tool.*
import ai.closepaw.tool.action.ActionOutcome
import ai.closepaw.tool.action.Target
import ai.closepaw.tool.action.TargetResolver
import org.json.JSONObject

/** Unified text input extension; absent input_method retains the existing type contract. */
internal class VerifiedInputInvocation(override val params: JSONObject, private val target: Target?) : ToolInvocation {
    override val toolName = "mobile_action"
    override fun getDescription(): String = if (params.optBoolean("clipboard_replace_authorized", false))
        "Type text using verified input; replace clipboard and leave the staged value (no automatic restore)"
        else "Type text using verified ${params.optString("input_method")} input"
    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) return ToolExecutionResult.Cancelled()
        val snapshot = context.currentSnapshot ?: return ToolExecutionResult.Failure("Fresh input snapshot required")
        if (target is Target.Coordinate || target is Target.ElementIndex && target.coordinateHint != null ||
            target is Target.Text && target.coordinateHint != null)
            return ToolExecutionResult.Failure("Verified text input requires a semantic target; coordinate fallback is disabled")
        val element = if (target == null) snapshot.elements.filter { it.isFocused }.singleOrNull() else {
            val resolved = TargetResolver.resolve(target, snapshot) as? TargetResolver.ResolveResult.Resolved
                ?: return ToolExecutionResult.Failure("Input target unavailable or ambiguous")
            snapshot.elements.filter { it.bounds == resolved.bounds }.singleOrNull()
        } ?: return ToolExecutionResult.Failure("Unique observed input target required")
        val result = context.platform.performVerifiedInput(VerifiedInputRequest(
            params.getString("input_text"), params.getString("input_method"), snapshot, element,
            params.optBoolean("clipboard_consent", false), params.optBoolean("clipboard_replace_authorized", false),
            context.approvedPackageName, context.clipboardReplacementApproved,
            params.optBoolean("terminal_input_authorized", false), context.terminalInputApproved
        ), context::isCancelled)
        return when (result) {
            is ActionOutcome.Success -> ToolExecutionResult.Success(result.message, result.observation)
            is ActionOutcome.Failed -> ToolExecutionResult.Failure(result.reason)
            is ActionOutcome.Cancelled -> ToolExecutionResult.Cancelled(result.reason)
        }
    }
}
