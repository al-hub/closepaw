package ai.closepaw.chatgpt

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GeminiTaskAwaiterTest {
    private fun task(state: String, operation: String = "gemini.question.ask") = buildJsonObject {
        put("state", state); put("operation_id", operation); put("stdout", "answer")
    }
    @Test fun pendingReadReturnsCompletionWithoutAnotherMcpRoundTrip() = runBlocking {
        var reads = 0; var waited = 0L
        val result = GeminiTaskAwaiter({ if (++reads < 4) task("running") else task("succeeded") }, { waited += it }).get(buildJsonObject {})
        assertEquals("succeeded", result.getValue("state").jsonPrimitive.content)
        assertEquals(300L, waited)
    }
    @Test fun terminalTaskAndOtherOperationsHaveNoAddedWait() = runBlocking {
        for (t in listOf(task("succeeded"), task("failed"), task("outcome_unknown"), task("running", "linux.shell.exec"))) {
            var waits = 0
            assertEquals(t, GeminiTaskAwaiter({ t }, { waits++ }).get(buildJsonObject {}))
            assertEquals(0, waits)
        }
    }
    @Test fun waitIsBoundedAndKeepsPaginationArguments() = runBlocking {
        var waits = 0; val args = buildJsonObject { put("task_id", "one"); put("offset", 100); put("limit", 20) }
        GeminiTaskAwaiter({ assertEquals(args, it); task("running") }, { waits++ }).get(args)
        assertEquals(80, waits)
    }
    @Test fun genericActionReadAlsoReturnsCompletionWithoutAnotherRoundTrip() = runBlocking {
        var reads = 0; var waited = 0L
        val result = GeminiTaskAwaiter({ if (++reads < 3) task("running", "android.ui.action") else task("succeeded", "android.ui.action") }, { waited += it }).get(buildJsonObject {})
        assertEquals("succeeded", result.getValue("state").jsonPrimitive.content)
        assertEquals(200L, waited)
    }
}
