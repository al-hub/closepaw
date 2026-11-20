package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class VisualSourceGateTest {
    @Test fun diagnosticsKeepTheOriginalGateOrderAndShortCircuit() {
        val conditions = VisualSourceCondition.entries
        for (firstFailure in conditions.indices + listOf(conditions.size)) {
            val visited = mutableListOf<Int>()
            fun flag(index: Int): () -> Boolean = { visited += index; index < firstFailure }
            val gate = VisualSourceGate(flag(0), flag(1), flag(2), flag(3))
            val expected = conditions.getOrNull(firstFailure)
            assertEquals(expected, gate.refusal())
            assertEquals((0..minOf(firstFailure, 3)).toList(), visited)
            visited.clear()
            assertEquals(expected == null, gate.allowed())
            assertEquals((0..minOf(firstFailure, 3)).toList(), visited)
        }
    }
}
