package ai.closepaw.tool.impl

import ai.closepaw.model.*
import ai.closepaw.platform.*
import ai.closepaw.tool.*
import ai.closepaw.tool.action.ActionOutcome
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VerifiedInputInvocationTest {
    @Test fun actualMobileToolRoutesMethodAndRouterProvenanceToUnifiedPlatform() = runTest {
        val bounds = Bounds(0, 0, 100, 50)
        val element = PerceptionElement(1, "", "example:id/input", "EditText", "", true, true, false, true, true, false, bounds, Point(50,25))
        val snapshot = ScreenSnapshot(System.currentTimeMillis(), listOf(element))
        val platform = mockk<AndroidPlatform>()
        var captured: VerifiedInputRequest? = null
        coEvery { platform.performVerifiedInput(any(), any()) } answers {
            captured = firstArg(); ActionOutcome.Success("verified", null, emptyList(), true)
        }
        val params = JSONObject("""{"action":"type","element_index":1,"input_text":"한글 A1!","input_method":"keyboard"}""")
        val tool = MobileActionTool(); assertEquals(ValidationResult.Valid, tool.validate(params))
        val result = tool.createInvocation(params).execute(object : ToolExecutionContext {
            override val platform = platform
            override val currentSnapshot = snapshot
            override val approvedPackageName = "example"
            override fun isCancelled() = false
        })
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("keyboard", captured!!.method); assertEquals("example", captured!!.approvedPackageName)
        assertFalse(captured!!.replacementApprovalVerified)
    }
    @Test fun invalidConsentAndDestructiveClearAreRejectedByActualSchemaValidator() {
        val tool = MobileActionTool()
        for (extra in listOf("\"clear\":true", "\"clipboard_consent\":true", "\"input_method\":\"unknown\"")) {
            val params = JSONObject("""{"action":"type","input_text":"hello","input_method":"keyboard"}""")
            val overrides = JSONObject("{$extra}"); overrides.keys().forEach { params.put(it, overrides.get(it)) }
            assertTrue(tool.validate(params) is ValidationResult.Invalid)
        }
    }
}
