package ai.closepaw.chatgpt.control
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class ControlTraceTest {
    @Test fun nestedMonotonicSpansDoNotReplayNullReturningBlock() = runBlocking {
        var time = 0L; var calls = 0
        val trace = ControlTrace("test") { time }
        withContext(trace) {
            controlSpan("parent") {
                time += 10
                val value: String? = controlSpan("nullable") { calls++; time += 20; null }
                assertNull(value); time += 30
            }
        }
        val spans = trace.snapshot().getValue("spans").jsonArray
        assertEquals(1,calls); assertEquals(2,spans.size)
        assertEquals(60L,spans[0].jsonObject.getValue("duration_ns").jsonPrimitive.long)
        assertEquals(0,spans[1].jsonObject.getValue("parent").jsonPrimitive.int)
        assertFalse(trace.snapshot().toString().contains("secret"))
    }
    @Test fun cancellationClosesSpanWithoutReplayingOrClaimingSuccess() = runBlocking {
        val trace = ControlTrace("cancel")
        try { withContext(trace) { controlSpan("native_action") { throw CancellationException("stop") } } }
        catch (_: CancellationException) { }
        assertEquals("interrupted", trace.snapshot().getValue("spans").jsonArray.single().jsonObject.getValue("outcome").jsonPrimitive.content)
    }
}
