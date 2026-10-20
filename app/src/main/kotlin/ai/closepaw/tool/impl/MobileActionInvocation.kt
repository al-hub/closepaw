package ai.closepaw.tool.impl

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ToolInvocation
import ai.closepaw.tool.action.ActionOutcome
import org.json.JSONObject

/**
 * Thin glue: routes to executor, maps ActionOutcome to ToolExecutionResult.
 *
 * ~40 lines. Replaces UIActionInvocation + all *TargetInvocation classes
 * for mobile_action.
 */
class MobileActionInvocation(
    override val params: JSONObject,
    private val description: String,
    private val executeAction: suspend (AndroidPlatform, ScreenSnapshot?, () -> Boolean, AppClassifier?) -> ActionOutcome
) : ToolInvocation {
    override val toolName = "mobile_action"

    private companion object {
        const val METRIC_TAG = "ClosePawActionLatency"
        val nextLocalSpan = AtomicLong()
    }

    override fun getDescription(): String {
        val thought = params.optString("agent_thought", "").trim()
        return if (thought.isNotEmpty()) "$description ($thought)" else description
    }

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) return ToolExecutionResult.Cancelled()
        // Local correlation and monotonic timing for the execution segment only.
        // Never log user input, screen content or accessibility node data.
        val localSpan = nextLocalSpan.incrementAndGet()
        val startedNs = System.nanoTime()
        var outcomeClass = "exception"
        try {
            val outcome = executeAction(
                context.platform, context.currentSnapshot, context::isCancelled, context.appClassifier
            )
            outcomeClass = when (outcome) {
                is ActionOutcome.Success -> "success"
                is ActionOutcome.Failed -> "failed"
                is ActionOutcome.Cancelled -> "cancelled"
            }
            return mapOutcome(outcome)
        } finally {
            val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(0L)
            // Failure to record a metric must never affect an action.
            runCatching {
                Log.i(METRIC_TAG, "local_span=$localSpan clock=monotonic " +
                    "segment=mobile_action_execute action_execute_ms=$elapsedMs outcome=$outcomeClass")
            }
        }
    }

    private fun mapOutcome(outcome: ActionOutcome): ToolExecutionResult = when (outcome) {
        is ActionOutcome.Success -> {
            val output = buildString {
                append(outcome.message)
                if (!outcome.verified) append(" [unverified]")
                if (outcome.attemptTrail.size > 1) {
                    append("\nAttempts: ${outcome.attemptTrail.joinToString(" -> ")}")
                }
            }
            ToolExecutionResult.Success(output = output, observation = outcome.observation)
        }
        is ActionOutcome.Failed -> {
            val output = buildString {
                append(outcome.reason)
                if (outcome.attemptTrail.size > 1) {
                    append("\nAttempts: ${outcome.attemptTrail.joinToString("; ")}")
                }
            }
            ToolExecutionResult.Failure(output)
        }
        is ActionOutcome.Cancelled -> ToolExecutionResult.Cancelled(outcome.reason)
    }
}
