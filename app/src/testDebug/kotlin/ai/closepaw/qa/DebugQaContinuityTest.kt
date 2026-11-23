package ai.closepaw.qa

import ai.closepaw.chatgpt.control.ControlFrame
import ai.closepaw.chatgpt.control.ControlNode
import android.app.Service
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** Regression for #147: Chrome counter omission must not kill a local FGS run. */
class DebugQaContinuityTest {
    private val chrome = "com.android.chrome"
    private fun node(id: Int = 2, resource: String = "search_box_text", text: String = "") =
        ControlNode(id = id, resourceId = "$chrome:id/$resource",
            sourcePackage = chrome, sourceWindowId = 42,
            clickable = true, enabled = true, empty = text.isEmpty(), text = text)
    private fun frame(vararg nodes: ControlNode) =
        ControlFrame(chrome, 42, "fresh", nodes.toList())
    private fun tab(count: Int) =
        ControlNode(id = 1, resourceId = "$chrome:id/tab_switcher_button",
            sourcePackage = chrome, sourceWindowId = 42,
            enabled = true, clickable = true, description = "$count tabs")

    @Test fun missingChromeTabCounterUsesConfirmedNewTabAndUniqueBlankNtpOnly() {
        val ntp = frame(node())
        assertNull(DebugOneStopQa.chromeTabCount(ntp))
        assertTrue(DebugOneStopQa.verifiedBlankAfterNewTabCommand(null, ntp, true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(null, ntp, false))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(null, frame(), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(null,
            frame(node(resource = "url_bar")), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(null,
            frame(node(text = "existing")), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(null,
            frame(node().copy(password = true)), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(null,
            frame(node(), node(id = 3)), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(null,
            ntp.copy(reason = "secure_window"), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(null,
            ntp.copy(truncated = true), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(null,
            ntp.copy(packageName = "another.app"), true))
    }

    @Test fun whenTabCountersExistTheyStillMustIncrementExactlyOne() {
        assertTrue(DebugOneStopQa.verifiedBlankAfterNewTabCommand(7,
            frame(tab(8), node()), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(7,
            frame(tab(7), node()), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(7,
            frame(tab(9), node()), true))
        // A disappeared counter is only acceptable for a confirmed action
        // plus the uniquely empty New Tab Page, never the address/url_bar.
        assertTrue(DebugOneStopQa.verifiedBlankAfterNewTabCommand(7,
            frame(node()), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(7,
            frame(node(resource = "url_bar")), true))
    }

    private fun result(outcome: String = "PASS", keys: Int = 6, touches: Int = 6,
        unknown: Boolean = false, goal: Boolean = true) = buildJsonObject {
        put("outcome", outcome); put("completed_keys", keys)
        put("touch_attempts", touches); put("outcome_unknown", unknown)
        put("goal_verified", goal)
    }

    @Test fun noFailedUnknownOrPartialRunMayStartNextRepetition() {
        assertEquals(3, DebugQaBatchPolicy.REPETITIONS)
        assertTrue(DebugQaBatchPolicy.mayProceedAfter(result()))
        assertFalse(DebugQaBatchPolicy.mayProceedAfter(result(outcome = "BLOCKED")))
        assertFalse(DebugQaBatchPolicy.mayProceedAfter(result(outcome = "UNKNOWN")))
        assertFalse(DebugQaBatchPolicy.mayProceedAfter(result(outcome = "IN_PROGRESS")))
        assertFalse(DebugQaBatchPolicy.mayProceedAfter(result(keys = 5)))
        assertFalse(DebugQaBatchPolicy.mayProceedAfter(result(touches = 5)))
        assertFalse(DebugQaBatchPolicy.mayProceedAfter(result(touches = 7)))
        assertFalse(DebugQaBatchPolicy.mayProceedAfter(result(unknown = true)))
        assertFalse(DebugQaBatchPolicy.mayProceedAfter(result(goal = false)))
        assertFalse(DebugQaBatchPolicy.mayProceedAfter(buildJsonObject { put("outcome", "PASS") }))
    }

    @Test fun independentQaOwnerIsAServiceNotActivityOrActivityCoroutine() {
        assertTrue(Service::class.java.isAssignableFrom(DebugQaRunnerService::class.java))
        assertFalse(android.app.Activity::class.java
            .isAssignableFrom(DebugQaRunnerService::class.java))
        // The runner is candidate-only and START_NOT_STICKY, so an Activity
        // recreation or process death never authorizes an implicit replay.
    }
}
