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

    @Test fun missingChromeTabCounterRequiresAcknowledgedCommandAndNewBlankNtpSurface() {
        val before = frame(node(resource = "url_bar"))
        val ntp = frame(node())
        assertNull(DebugOneStopQa.chromeTabCount(ntp))
        assertTrue(DebugOneStopQa.verifiedBlankAfterNewTabCommand(before, ntp, true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(before, ntp, false))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(before, before, true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(ntp, ntp, true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(before, frame(), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(before, frame(node(text = "existing")), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(before, frame(node().copy(password = true)), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(before, frame(node(), node(id = 3)), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(before, ntp.copy(reason = "secure_window"), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(before, ntp.copy(truncated = true), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(before, ntp.copy(packageName = "another.app"), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(before, ntp.copy(windowId = 43), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(before, ntp.copy(configurationRevision = 2), true))
    }

    @Test fun whenTabCountersExistTheyStillMustIncrementExactlyOne() {
        val before = frame(tab(7), node())
        assertTrue(DebugOneStopQa.verifiedBlankAfterNewTabCommand(before, frame(tab(8), node()), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(before, frame(tab(7), node()), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(before, frame(tab(9), node()), true))
        assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(before, frame(node()), true))
        val priorEditor = frame(tab(7), node(resource = "url_bar"))
        assertTrue(DebugOneStopQa.verifiedBlankAfterNewTabCommand(priorEditor, frame(node()), true))
    }

    private fun blankBarWithoutToolbar() = frame(
        node(resource = "url_bar").copy(editable = true, focused = true),
        *(10..69).map { node(id = it, resource = "fixture").copy(clickable = false) }.toTypedArray(),
    )

    @Test fun observedMenuAbsenceAfterSecureWindowRecoveryUsesOneRemainingPassiveRead() =
        kotlinx.coroutines.runBlocking {
            val blank = blankBarWithoutToolbar()
            val frames = ArrayDeque(listOf(blank.copy(reason = "secure_window_changed"), blank, blank))
            val reasons = mutableListOf<String>()
            val result = DebugOneStopQa.captureChromeTransition(
                capture = { frames.removeFirst() }, inspect = { facts() },
                allowed = { true }, pause = {}, onRetry = { reasons += it },
                needsToolbar = { DebugOneStopQa.chromeMenuButton(it) == null },
            )
            assertEquals(3, result.reads)
            assertEquals(listOf("secure_window_changed", "chrome_toolbar_settling"), reasons)
            assertEquals(61, DebugOneStopQa.chromeSelectorFacts(result.frame).chromeNodeCount)
            assertEquals(1, DebugOneStopQa.chromeSelectorFacts(result.frame).focusedUrlBarCount)
            assertNull(DebugOneStopQa.chromeMenuButton(result.frame))
            assertFalse(DebugOneStopQa.verifiedBlankAfterNewTabCommand(blank, result.frame, false))
        }

    @Test fun toolbarMayRecoverInSameWindowButSecurityRefusalOrWindowChangeStopsProbing() =
        kotlinx.coroutines.runBlocking {
            val blank = blankBarWithoutToolbar()
            val menu = node(resource = "menu_button").copy(id = 90)
            val ready = blank.copy(nodes = blank.nodes + menu)
            val framesToCheck = listOf(ready, ready.copy(reason = "secure_window"), ready.copy(windowId = 43))
            for ((index, next) in framesToCheck.withIndex()) {
                val frames = ArrayDeque(listOf(blank, next))
                val result = DebugOneStopQa.captureChromeTransition(
                    capture = { frames.removeFirst() }, inspect = { facts() }, allowed = { true }, pause = {},
                    needsToolbar = { DebugOneStopQa.chromeMenuButton(it) == null },
                )
                assertEquals(2, result.reads)
                assertEquals(if (index == 0) menu else null, DebugOneStopQa.chromeMenuButton(result.frame))
                assertEquals(listOf(null, "secure_window", "chrome_toolbar_context_changed")[index], result.frame.reason)
            }
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

    @Test fun toolbarBaselinePreservedAcrossTransientDeniedSentinelWithinThreeReadBudget() =
        kotlinx.coroutines.runBlocking {
            val blank = blankBarWithoutToolbar()
            val menu = node(resource = "menu_button").copy(id = 90)
            val recovered = blank.copy(nodes = blank.nodes + menu)
            val transientDenied = ControlFrame("", -1, "", emptyList(), reason = "secure_window_changed")

            // 1) Valid -> transient denied sentinel -> same-context valid (uses 3rd allowed read, no budget extension)
            run {
                val frames = ArrayDeque(listOf(blank, transientDenied, recovered))
                val retries = mutableListOf<String>()
                val res = DebugOneStopQa.captureChromeTransition(
                    capture = { frames.removeFirst() }, inspect = { facts() },
                    allowed = { true }, pause = {}, onRetry = { retries += it },
                    needsToolbar = { DebugOneStopQa.chromeMenuButton(it) == null },
                )
                assertEquals(3, res.reads)
                assertEquals(2, res.recoveryReads)
                assertEquals(listOf("chrome_toolbar_settling", "secure_window_changed"), retries)
                assertNull(res.frame.reason)
                assertEquals("secure_window_changed", res.firstRefusal)
                assertEquals(menu.id, DebugOneStopQa.chromeMenuButton(res.frame)?.id)
            }

            // 2) Valid -> transient denied sentinel -> genuinely changed valid context on 3rd read -> refused against Read-1 baseline
            for (shifted in listOf(
                recovered.copy(windowId = 43),
                recovered.copy(packageName = "other.app"),
                recovered.copy(configurationRevision = blank.configurationRevision + 1),
            )) {
                val frames = ArrayDeque(listOf(blank, transientDenied, shifted))
                val res = DebugOneStopQa.captureChromeTransition(
                    capture = { frames.removeFirst() }, inspect = { facts() },
                    allowed = { true }, pause = {},
                    needsToolbar = { DebugOneStopQa.chromeMenuButton(it) == null },
                )
                assertEquals(3, res.reads)
                assertEquals(2, res.recoveryReads)
                assertEquals("chrome_toolbar_context_changed", res.frame.reason)
                assertEquals("secure_window_changed", res.firstRefusal)
            }

            // 3) Valid -> repeated transient refusal -> terminates at 3 reads with refusal (no budget extension)
            run {
                val frames = ArrayDeque(listOf(blank, transientDenied, transientDenied))
                val res = DebugOneStopQa.captureChromeTransition(
                    capture = { frames.removeFirst() }, inspect = { facts() },
                    allowed = { true }, pause = {},
                    needsToolbar = { DebugOneStopQa.chromeMenuButton(it) == null },
                )
                assertEquals(3, res.reads)
                assertEquals(2, res.recoveryReads)
                assertEquals("secure_window_changed", res.frame.reason)
                assertEquals("secure_window_changed", res.firstRefusal)
            }

            // 4) Valid -> actual secure_window sentinel -> stops immediately at 2 reads (never takes 3rd read)
            run {
                val secureDenied = ControlFrame("", -1, "", emptyList(), reason = "secure_window")
                val frames = ArrayDeque(listOf(blank, secureDenied, recovered))
                val res = DebugOneStopQa.captureChromeTransition(
                    capture = { frames.removeFirst() }, inspect = { facts() },
                    allowed = { true }, pause = {},
                    needsToolbar = { DebugOneStopQa.chromeMenuButton(it) == null },
                )
                assertEquals(2, res.reads)
                assertEquals(1, res.recoveryReads)
                assertEquals("secure_window", res.frame.reason)
                assertEquals("secure_window", res.firstRefusal)
            }

            // 5) Authority revoked after Read-2 transient sentinel -> stops immediately at 2 reads
            run {
                var live = true
                var reads = 0
                val frames = ArrayDeque(listOf(blank, transientDenied, recovered))
                val res = DebugOneStopQa.captureChromeTransition(
                    capture = {
                        reads++
                        val next = frames.removeFirst()
                        if (reads == 2) live = false
                        next
                    },
                    inspect = { facts() },
                    allowed = { live },
                    pause = {},
                    needsToolbar = { DebugOneStopQa.chromeMenuButton(it) == null },
                )
                assertEquals(2, res.reads)
                assertEquals(1, res.recoveryReads)
                assertEquals("secure_window_changed", res.frame.reason)
            }
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
