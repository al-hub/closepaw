package ai.closepaw.chatgpt.control

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*

/** Invocation-local nested monotonic spans. No screen content or target arguments. */
internal class ControlTrace(val correlationId: String, private val now: () -> Long = System::nanoTime) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ControlTrace>
    private val origin = now()
    private val spans = mutableListOf<JsonObject>()
    private var parent = -1
    var step = 0
    var completed = 0
    var inFlight = false
    var phase = "admission"
    suspend fun <T> measure(name: String, block: suspend () -> T): T {
        val start = now(); val previous = parent; val previousPhase = phase
        val id = spans.size; val atStep = step
        spans.add(buildJsonObject {})
        parent = id; phase = name
        var outcome = "interrupted"
        try { return block().also { outcome = "returned" } }
        finally {
            val end = now()
            spans[id] = buildJsonObject {
                put("id", id); put("parent", previous); put("step", atStep); put("phase", name)
                put("start_ns", (start - origin).coerceAtLeast(0)); put("duration_ns", (end - start).coerceAtLeast(0))
                put("outcome", outcome)
            }
            parent = previous; phase = previousPhase
        }
    }
    fun snapshot() = buildJsonObject {
        put("correlation_id", correlationId); put("clock_domain", "monotonic_process")
        put("total_ns", (now() - origin).coerceAtLeast(0)); put("completed_steps", completed)
        put("in_flight_outcome_unknown", inFlight); put("voice_e2e", "not_measured")
        put("aggregation", "nested spans; sum only siblings; parent minus direct children is unattributed")
        put("spans", JsonArray(spans.toList()))
    }
}
internal suspend fun <T> controlSpan(name: String, block: suspend () -> T): T {
    val trace = currentCoroutineContext()[ControlTrace]
    return timedControlStage(name) { if (trace == null) block() else trace.measure(name, block) }
}
