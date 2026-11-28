package ai.closepaw.qa

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DebugContinuousReadOnlyTest {
    private val policy = DebugContinuousReadOnly

    @Test fun galaxyMenuFailureContinuesIndependentSafeObservations() = runBlocking {
        assertTrue(policy.mayInspect("unique_chrome_menu_unavailable", true, false, true, true))
        val stored = mutableListOf<DebugContinuousReadOnly.Finding>()
        val probes = listOf(
            DebugContinuousReadOnly.Probe("chrome_menu") {
                DebugContinuousReadOnly.Finding("chrome_menu", "FAILED", "unique_chrome_menu_unavailable")
            },
            DebugContinuousReadOnly.Probe("accessibility_window") {
                DebugContinuousReadOnly.Finding("accessibility_window", "PASS", "chrome_root_observed")
            },
            DebugContinuousReadOnly.Probe("keyboard_image") {
                DebugContinuousReadOnly.Finding("keyboard_image", "NOT_OBSERVABLE", "keyboard_not_visible")
            },
        )
        val result = policy.collect(probes, { true }, stored::add)
        assertEquals(3, result.size)
        assertEquals("PASS", result[1].status)
        assertEquals("NOT_OBSERVABLE", result[2].status)
        assertEquals(result, stored)
        assertFalse(result[0].redactedJson().toString().contains("https://"))
    }

    @Test fun isolatedFailureDoesNotSkipLaterReadOnlyStep() = runBlocking {
        val findings = policy.collect(listOf(
            DebugContinuousReadOnly.Probe("fault") { error("sensitive user value") },
            DebugContinuousReadOnly.Probe("next") { DebugContinuousReadOnly.Finding("next", "PASS", "observed") },
        ), { true }, {})
        assertEquals("diagnostic_exception", findings.first().reason)
        assertEquals("PASS", findings.last().status)
        assertFalse(findings.toString().contains("sensitive user value"))
    }

    @Test fun stopOrRevocationPreventsRemainingProbe() = runBlocking {
        var live = true
        var secondRan = false
        val findings = policy.collect(listOf(
            DebugContinuousReadOnly.Probe("first") {
                live = false
                DebugContinuousReadOnly.Finding("first", "PASS", "observed")
            },
            DebugContinuousReadOnly.Probe("second") {
                secondRan = true
                DebugContinuousReadOnly.Finding("second", "PASS", "observed")
            },
        ), { live }, {})
        assertEquals(1, findings.size)
        assertEquals("PERMISSION_REVOKED", findings.single().status)
        assertFalse(secondRan)
    }

    @Test fun sensitiveOrUnsafeStateNeverStartsContinuation() {
        for (reason in listOf("secure_window", "device_locked", "permission_revoked",
            "control_authority_revoked", "preflight_password_editor", "preflight_editor_not_empty",
            "chrome_menu_unconfirmed", "unknown", "screen_changed")) {
            assertFalse(policy.mayInspect(reason, true, false, true, true))
        }
        assertFalse(policy.mayInspect("unique_chrome_menu_unavailable", false, false, true, true))
        assertFalse(policy.mayInspect("unique_chrome_menu_unavailable", true, true, true, true))
        assertFalse(policy.mayInspect("unique_chrome_menu_unavailable", true, false, false, true))
        assertFalse(policy.mayInspect("unique_chrome_menu_unavailable", true, false, true, false))
    }

    @Test fun explicitUnknownStopsProbeChain() = runBlocking {
        var touched = false
        val findings = policy.collect(listOf(
            DebugContinuousReadOnly.Probe("unsafe") { DebugContinuousReadOnly.Finding("unsafe", "PERMISSION_REVOKED", "device_locked") },
            DebugContinuousReadOnly.Probe("never") {
                touched = true
                DebugContinuousReadOnly.Finding("never", "PASS", "unexpected")
            },
        ), { true }, {})
        assertEquals(1, findings.size)
        assertFalse(touched)
    }
}
