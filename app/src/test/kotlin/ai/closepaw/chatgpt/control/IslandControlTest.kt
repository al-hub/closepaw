package ai.closepaw.chatgpt.control

import ai.closepaw.ui.overlay.IslandPosition
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class IslandControlTest {
    private class Port : IslandControlPort {
        var bounds: IslandBounds? = IslandBounds(100, 100, 200, 60, 1080, 1920)
        var refusal: String? = null
        var writes = 0
        var waitMs = 0L
        var move = true
        var acknowledge = true
        override suspend fun observeIsland() = bounds
        override fun islandRefusal(sessionId: String) = refusal
        override suspend fun dragIsland(before: IslandBounds, dx: Int, dy: Int, holdMs: Long, moveMs: Long, sessionId: String): Boolean {
            writes++; delay(waitMs)
            if (move) bounds = before.copy(x = before.x + dx, y = before.y + dy)
            return acknowledge
        }
    }
    @Test fun observationIsRequiredAndConsumedEvenWhenResultIsUnknown() = runTest {
        val p = Port(); val c = IslandControl(p, { 100L })
        assertFalse(c.drag("session", "missing", 30, 40, 450, 100).success)
        val id = c.observe("session").data.getValue("observation_id").toString().trim('"')
        p.acknowledge = false
        assertEquals("true", c.drag("session", id, 30, 40, 450, 100).data["outcome_unknown"].toString())
        assertFalse(c.drag("session", id, 30, 40, 450, 100).success)
        assertEquals(1, p.writes)
    }
    @Test fun acknowledgementWithoutMovementCannotPass() = runTest {
        val p = Port().apply { move = false }; val c = IslandControl(p)
        val id = c.observe("s").data.getValue("observation_id").toString().trim('"')
        assertFalse(c.drag("s", id, 20, 20, 450, 100).success)
    }
    @Test fun realObservedMovementPasses() = runTest {
        val p = Port(); val c = IslandControl(p)
        val id = c.observe("s").data.getValue("observation_id").toString().trim('"')
        assertTrue(c.drag("s", id, 20, 20, 450, 100).success)
        assertEquals(IslandPosition(120,120), p.bounds!!.position)
    }
    @Test fun staleGeometryWrongSessionAndExpiredObservationsNeverWrite() = runTest {
        for (mode in 0..2) {
            val p = Port(); var now = 0L; val c = IslandControl(p, { now })
            val id = c.observe("s").data.getValue("observation_id").toString().trim('"')
            if (mode == 0) p.bounds = p.bounds!!.copy(y = 120)
            if (mode == 2) now = 60_001
            assertFalse(c.drag(if (mode == 1) "other" else "s", id, 20, 20, 450, 100).success)
            assertEquals(0, p.writes)
        }
    }
    @Test fun shortHoldJitterOutOfBoundsAndOverflowNeverWrite() = runTest {
        for (values in listOf(listOf(30,30,449), listOf(7,7,450), listOf(-101,0,450), listOf(Int.MAX_VALUE,0,450))) {
            val p = Port(); val c = IslandControl(p)
            val id = c.observe("s").data.getValue("observation_id").toString().trim('"')
            assertFalse(c.drag("s", id, values[0], values[1], values[2].toLong(), 100).success)
            assertEquals(0, p.writes)
        }
    }
    @Test fun endedSessionDoesNotObserveOrMove() = runTest {
        val p = Port().apply { refusal = "control_session_ended" }; val c = IslandControl(p)
        assertFalse(c.observe("s").success)
        assertFalse(c.drag("s", "none", 30, 30, 450, 100).success)
        assertEquals(0, p.writes)
    }
    @Test fun timeoutIsUnknownAndCannotReplay() = runTest {
        val p = Port().apply { waitMs = 60_000 }; val c = IslandControl(p)
        val id = c.observe("s").data.getValue("observation_id").toString().trim('"')
        val r = c.drag("s", id, 20, 20, 450, 100)
        assertEquals("true", r.data["outcome_unknown"].toString())
        assertFalse(c.drag("s", id, 20, 20, 450, 100).success)
        assertEquals(1, p.writes)
    }
}
