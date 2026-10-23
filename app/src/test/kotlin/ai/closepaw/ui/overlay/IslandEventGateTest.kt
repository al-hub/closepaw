package ai.closepaw.ui.overlay

import org.junit.Assert.*
import org.junit.Test

class IslandEventGateTest {
    @Test fun taskSessionTimeAndSingleUseActionMustAllMatch() {
        val g = IslandEventGate()
        g.start("s", "one", 100)
        g.proposed("s", "a", 101)
        assertFalse(g.completed("other", "a", 102))
        assertTrue(g.completed("s", "a", 102))
        assertFalse(g.completed("s", "a", 103))
        g.proposed("s", "old", 104)
        g.start("s", "two", 200)
        assertFalse(g.completed("s", "old", 201))
        g.proposed("s", "stale", 150)
        assertFalse(g.completed("s", "stale", 202))
        g.proposed("s", "new", 203)
        g.end("other", "two")
        assertTrue(g.completed("s", "new", 204))
        g.proposed("s", "late", 205)
        g.end("s", "two")
        assertFalse(g.completed("s", "late", 206))
    }
}
