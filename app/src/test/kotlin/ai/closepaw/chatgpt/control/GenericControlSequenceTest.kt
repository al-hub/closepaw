package ai.closepaw.chatgpt.control

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GenericControlSequenceTest {
    private class CalculatorPort : ControlPort {
        val sessionValue = ControlSession().also { check(it.start()) }
        override fun session(): ControlSession = sessionValue
        var packageName = "com.sec.android.app.popupcalculator"
        var writes = 0
        var endAfterFirst = false
        var duplicateDigit = false
        var unknownEffect = false
        var changeAppAfterFirst = false

        override suspend fun capture(): ControlFrame {
            val digits = (0..9).map { ControlNode(it, it.toString(), clickable = true) }
            val nodes = if (duplicateDigit) digits + ControlNode(100, "1", clickable = true) else digits
            return ControlFrame(packageName, 7, "calculator-$writes", nodes)
        }
        override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
            writes++
            if (changeAppAfterFirst) packageName = "example.other.app"
            if (endAfterFirst) sessionValue.end()
            return if (unknownEffect) ControlEffect(true, false, "unknown")
                else ControlEffect(true, true, "acknowledged")
        }
        override suspend fun apps(filter: String) = JsonArray(emptyList())
    }

    private suspend fun execute(
        port: CalculatorPort,
        labels: List<String> = listOf("1", "2", "3"),
        ready: () -> Boolean = { true },
    ) = GenericControlSequence(GenericControl(port, { true }), { port.sessionValue }, ready)
        .run(labels, port.sessionValue.id)

    @Test fun executesNineFreshlyValidatedCalculatorDigitsOnce() = runBlocking {
        val port = CalculatorPort()
        val result = execute(port, (1..9).map(Int::toString))
        assertTrue(result.summary, result.success)
        assertEquals(9, port.writes)
        assertEquals(9, result.data.getValue("completed_steps").jsonPrimitive.int)
        assertEquals("monotonic_process", result.data.getValue("clock_domain").jsonPrimitive.content)
        assertTrue(result.data.getValue("elapsed_ms").jsonPrimitive.long >= 0L)
        assertEquals(JsonPrimitive(false), result.data["goal_verified"])
    }

    @Test fun physicalEndStopsTheRemainingQueue() = runBlocking {
        val port = CalculatorPort().apply { endAfterFirst = true }
        val result = execute(port)
        assertFalse(result.success)
        assertEquals("control_session_changed", result.data["reason_code"]?.jsonPrimitive?.content)
        assertEquals(1, port.writes)
        assertEquals("monotonic_process", result.data.getValue("clock_domain").jsonPrimitive.content)
    }

    @Test fun ambiguousNodeFailsBeforeAnyClick() = runBlocking {
        val port = CalculatorPort().apply { duplicateDigit = true }
        val result = execute(port)
        assertEquals("target_not_unique", result.data["reason_code"]?.jsonPrimitive?.content)
        assertEquals(0, port.writes)
    }

    @Test fun unconfirmedClickNeverReplays() = runBlocking {
        val port = CalculatorPort().apply { unknownEffect = true }
        val result = execute(port)
        assertFalse(result.success)
        assertEquals(JsonPrimitive(true), result.data["outcome_unknown"])
        assertEquals(1, port.writes)
    }

    @Test fun nonCalculatorAndNonDigitPlansFailClosed() = runBlocking {
        val port = CalculatorPort().apply { packageName = "example.shopping" }
        assertEquals("calculator_only", execute(port).data["reason_code"]?.jsonPrimitive?.content)
        assertEquals(0, port.writes)
        port.packageName = "com.sec.android.app.popupcalculator"
        assertEquals("invalid_sequence", execute(port, listOf("Buy", "1")).data["reason_code"]?.jsonPrimitive?.content)
        assertEquals(0, port.writes)
    }

    @Test fun permissionLossPreventsAnyEffect() = runBlocking {
        val port = CalculatorPort()
        assertEquals("control_session_changed",
            execute(port, ready = { false }).data["reason_code"]?.jsonPrimitive?.content)
        assertEquals(0, port.writes)
    }

    @Test fun foregroundSwitchStopsAfterFirstAction() = runBlocking {
        val port = CalculatorPort().apply { changeAppAfterFirst = true }
        val result = execute(port)
        assertFalse(result.success)
        assertEquals("foreground_changed", result.data["reason_code"]?.jsonPrimitive?.content)
        assertEquals(1, port.writes)
    }

    @Test fun consentRevocationStopsRemainingClicks() = runBlocking {
        val port = CalculatorPort()
        val result = execute(port, ready = { port.writes == 0 })
        assertFalse(result.success)
        assertEquals("control_session_changed", result.data["reason_code"]?.jsonPrimitive?.content)
        assertEquals(1, port.writes)
    }
}
