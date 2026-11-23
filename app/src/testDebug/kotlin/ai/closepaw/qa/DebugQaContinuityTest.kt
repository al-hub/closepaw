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


    private fun facts(chromeRoot: Boolean = true, active: Boolean = true, focused: Boolean = true) =
        DebugOneStopQa.ChromeWindowFacts(chromeRoot, true, true,
            active, focused, true, true, false)

    @Test fun foregroundApplicationRequiredIsReadOnlyResampledWithBoundedFreshNativeCapture() =
        kotlinx.coroutines.runBlocking {
            // Emulates the observed Galaxy run: root=Chrome but native app
            // window not yet focused, followed by a fully verified Chrome frame.
            val frames = ArrayDeque(listOf(
                frame().copy(reason = "foreground_application_required"),
                frame().copy(reason = "foreground_application_required"),
                frame(node()),
            ))
            var captures = 0
            var renewed = 0
            val result = DebugOneStopQa.captureChromeTransition(
                capture = { captures++; frames.removeFirst() },
                inspect = { facts(active = true, focused = false) },
                allowed = { true }, pause = { }, onRetry = { renewed++ },
            )
            assertNull(result.frame.reason)
            assertEquals(3, captures)
            assertEquals(2, result.recoveryReads)
            assertEquals(2, renewed)
            assertEquals("foreground_application_required", result.firstRefusal)
            assertFalse(result.windowFactsAtRefusal!!.focused)
        }

    @Test fun unresolvedForegroundOrNonChromeCannotBypassSafetyOrLoop() =
        kotlinx.coroutines.runBlocking {
            var reads = 0
            val blocked = DebugOneStopQa.captureChromeTransition(
                capture = { reads++; frame().copy(reason = "foreground_application_required") },
                inspect = { facts() }, allowed = { true }, pause = {},
            )
            assertEquals(3, reads)
            assertEquals(2, blocked.recoveryReads)
            assertEquals("foreground_application_required", blocked.frame.reason)
            var changedAppReads = 0
            val changed = DebugOneStopQa.captureChromeTransition(
                capture = { changedAppReads++; frame().copy(reason = "foreground_application_required") },
                inspect = { facts(chromeRoot = false) }, allowed = { true }, pause = {},
            )
            assertEquals(1, changedAppReads)
            assertEquals(0, changed.recoveryReads)
            assertEquals("foreground_application_required", changed.frame.reason)
        }

    @Test fun secureWindowAndRevocationNeverTriggerReadReplay() =
        kotlinx.coroutines.runBlocking {
            for (denied in listOf("secure_window", "screen_share_revoked", "device_locked",
                    "blocked_or_cautious_app", "generic_control_disabled")) {
                var calls = 0
                val res = DebugOneStopQa.captureChromeTransition(
                    capture = { calls++; frame().copy(reason = denied) },
                    inspect = { facts() }, allowed = { true }, pause = {},
                )
                assertEquals(denied, 1, calls)
                assertEquals(0, res.recoveryReads)
                assertEquals(denied, res.frame.reason)
            }
            var active = true
            var calls = 0
            val revoked = DebugOneStopQa.captureChromeTransition(
                capture = { calls++; frame().copy(reason = "foreground_application_required") },
                inspect = { facts() }, allowed = { active },
                pause = { active = false },
            )
            assertEquals(1, calls)
            assertEquals(0, revoked.recoveryReads)
        }

    @Test fun chromeForegroundDiagnosticsExcludeAllContentAndPackageNames() {
        val detail = facts(active = false, focused = false).redactedJson().toString()
        assertTrue(detail.contains("\"active\":false"))
        assertTrue(detail.contains("\"focused\":false"))
        listOf("text", "url", "bounds", "password", "other.app", "com.android.chrome")
            .forEach { assertFalse(it, detail.contains(it)) }
        for (count in 0..5) {
            assertEquals(count in 1..2,
                DebugOneStopQa.mayResampleChromeTransition(
                    "foreground_application_required", true, count))
            assertFalse(DebugOneStopQa.mayResampleChromeTransition(
                "foreground_application_required", false, count))
        }
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
