package ai.closepaw.tool.action

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ObservedTypeExecutorTest {
    private fun evidence() = UnifiedTextInputPolicy.Evidence(true, true, true, true, true, true, true, false, true)
    @Test fun unknownDirectResultIsReturnedWithoutDispatchingAnotherChannel() = runBlocking {
        val calls = mutableListOf<UnifiedTextInputPolicy.Channel>()
        val result = TypeExecutor().executeObservedInput(evidence(), { calls.add(it); "unknown" }, { "denied" })
        assertEquals("unknown", result); assertEquals(listOf(UnifiedTextInputPolicy.Channel.DIRECT), calls)
    }
    @Test fun unattemptedCapabilitySelectionRespectsPriorityAndRevocation() = runBlocking {
        val cases = listOf(evidence().copy(directSupported = false) to UnifiedTextInputPolicy.Channel.CLIPBOARD_PASTE,
            evidence().copy(directSupported = false, clipboardAllowed = false) to UnifiedTextInputPolicy.Channel.VISIBLE_IME)
        for ((evidence, expected) in cases) {
            val result = TypeExecutor().executeObservedInput(evidence, { it }, { null })
            assertEquals(expected, result)
        }
        var calls = 0
        assertNull(TypeExecutor().executeObservedInput(evidence().copy(sessionActive = false), { calls++; it }, { null }))
        assertEquals(0, calls)
    }
}
