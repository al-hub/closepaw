package ai.closepaw.chatgpt.control

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*

/** Flat invocation-local events: parallel callbacks never mutate ControlTrace's span stack.
 * Only fixed phase names, generated command/probe IDs and relative monotonic times are exported.
 * Saturation is explicit; it must never block, authorize an action, or affect a security verdict.
 */
internal class ControlTiming(
    private val now: () -> Long,
    private val capacity: Int = 2048,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ControlTiming> {
        private val phases = setOf("initial_observation", "semantic_lookup", "guarded_action",
            "native_action", "post_observation", "result_validation", "security_probe",
            "probe_api_spacing", "fingerprint_traversal", "fresh_fingerprint_and_action", "intent_cue_wait")
    }
    enum class Event {
        STAGE_START, STAGE_END, PROBE_QUEUED, PROBE_SLOT_READY, MAIN_ENTERED,
        PROBE_DISPATCH, PROBE_CALLBACK_OK, PROBE_CALLBACK_FAILED, PROBE_EXCEPTION,
        PROBE_CANCELLED, GESTURE_DISPATCH, GESTURE_CALLBACK_OK, GESTURE_CALLBACK_CANCELLED,
        NODE_CLICK_DISPATCH, NODE_CLICK_RETURNED,
        GUARDED_SNAPSHOT_AVAILABLE, CANCEL_OBSERVED, INVOCATION_FINISHED,
    }
    private val origin = now()
    private val events = mutableListOf<JsonObject>()
    private var nextId = 0
    private var dropped = 0
    init { require(capacity in 1..8192) }
    @Synchronized fun nextId(): Int = ++nextId
    @Synchronized fun mark(event: Event, id: Int = 0, step: Int = 0, phase: String? = null) {
        if (events.size >= capacity) { dropped++; return }
        events.add(buildJsonObject {
            put("event", event.name); put("id", id); put("step", step)
            phase?.let { put("phase", if (it in phases) it else "other") }
            put("at_ns", (now() - origin).coerceAtLeast(0))
        })
    }
    @Synchronized fun snapshot() = buildJsonObject {
        put("clock_domain", "android_elapsed_realtime_ns")
        put("timestamps", "relative_to_invocation_origin; never subtract from ControlTrace nanoTime")
        put("dropped_events", dropped); put("buffer_complete", dropped == 0)
        put("events", JsonArray(events.toList()))
    }
}

/** Retains the existing synchronous span tree; flat timestamps describe overlap separately. */
internal suspend fun <T> timedControlStage(name: String, block: suspend () -> T): T {
    val context = currentCoroutineContext()
    val timing = context[ControlTiming] ?: return block()
    val id = timing.nextId()
    val step = context[ControlTrace]?.step ?: 0
    timing.mark(ControlTiming.Event.STAGE_START, id, step, name)
    try { return block() } finally { timing.mark(ControlTiming.Event.STAGE_END, id, step, name) }
}
