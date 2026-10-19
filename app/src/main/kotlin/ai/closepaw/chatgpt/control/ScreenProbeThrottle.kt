package ai.closepaw.chatgpt.control

import kotlinx.coroutines.delay

/** Invocation-serialized API spacing; elapsed work counts toward the gap. Never caches security results. */
internal class ScreenProbeThrottle(private val now: () -> Long, private val pause: suspend (Long) -> Unit = { delay(it) }) {
    private var previous: Long? = null
    fun reset() { previous = null }
    suspend fun awaitSlot() {
        // The first probe may follow a screenshot made by another adapter.
        val remaining = previous?.let { 400L - (now() - it) } ?: 400L
        if (remaining > 0) pause(remaining)
        previous = now()
    }
}
