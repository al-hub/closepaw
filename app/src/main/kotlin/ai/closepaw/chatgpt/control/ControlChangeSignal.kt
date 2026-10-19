package ai.closepaw.chatgpt.control

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Service-owned, text-free event generation. StateFlow closes the check/subscribe race. */
internal class ControlChangeSignal {
    private val generation = MutableStateFlow(0L)
    fun version(): Long = generation.value
    fun changed() { generation.value += 1 }
    suspend fun awaitAfter(version: Long, timeoutMs: Long): Boolean =
        generation.value != version || withTimeoutOrNull(timeoutMs) {
            generation.first { it != version }; true
        } == true
}
