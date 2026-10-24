package ai.closepaw.chatgpt.operations

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.JsonObject

/** Invocation-local handoff. Cancellation still propagates unchanged; no global task map or replay.
 * The dispatcher reads this only after the handler unwinds, including structured child jobs/cleanup. */
internal class OperationInterruptionEvidence : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OperationInterruptionEvidence>
    @Volatile var snapshot: JsonObject? = null
}
