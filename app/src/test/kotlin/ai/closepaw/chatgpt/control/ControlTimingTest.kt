package ai.closepaw.chatgpt.control

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ControlTimingTest {
    @Test fun phasesUseOneRelativeClockAndDoNotExportArbitraryText() = runBlocking {
        var clock = 100L
        val timing = ControlTiming({ clock })
        withContext(timing) { timedControlStage("secret screen text") { clock += 25 } }
        val snapshot = timing.snapshot()
        val events = snapshot.getValue("events").jsonArray
        assertEquals(0L, events[0].jsonObject.getValue("at_ns").jsonPrimitive.long)
        assertEquals(25L, events[1].jsonObject.getValue("at_ns").jsonPrimitive.long)
        assertFalse(snapshot.toString().contains("secret"))
        assertEquals("other", events[0].jsonObject.getValue("phase").jsonPrimitive.content)
    }
    @Test fun callbacksAreBoundedAndSnapshotsCannotBeMutatedByLateCallback() {
        var clock = 0L
        val timing = ControlTiming({ clock++ }, capacity = 2)
        val id = timing.nextId()
        timing.mark(ControlTiming.Event.PROBE_DISPATCH, id, 1)
        val before = timing.snapshot()
        timing.mark(ControlTiming.Event.PROBE_CANCELLED, id, 1)
        timing.mark(ControlTiming.Event.PROBE_CALLBACK_OK, id, 1)
        assertEquals(1, before.getValue("events").jsonArray.size)
        assertEquals(1, timing.snapshot().getValue("dropped_events").jsonPrimitive.int)
        assertFalse(timing.snapshot().getValue("buffer_complete").jsonPrimitive.boolean)
    }
    @Test fun parallelCallbacksKeepUniqueIdsAndDoNotUseNestedSpanParent() = runBlocking {
        val timing = ControlTiming(System::nanoTime)
        coroutineScope {
            repeat(100) { launch(Dispatchers.Default) {
                timing.mark(ControlTiming.Event.PROBE_CALLBACK_OK, timing.nextId())
            } }
        }
        val events = timing.snapshot().getValue("events").jsonArray
        assertEquals(100, events.map { it.jsonObject.getValue("id").jsonPrimitive.int }.toSet().size)
        assertFalse(events.toString().contains("parent"))
    }
    @Test fun cancelledStageClosesWithoutReplay() = runBlocking {
        val timing = ControlTiming(System::nanoTime)
        var calls = 0
        try { withContext(timing) { timedControlStage("native_action") { calls++; throw CancellationException() } } }
        catch (_: CancellationException) { }
        assertEquals(1, calls)
        assertEquals(listOf("STAGE_START", "STAGE_END"), timing.snapshot().getValue("events").jsonArray.map {
            it.jsonObject.getValue("event").jsonPrimitive.content
        })
    }
}
