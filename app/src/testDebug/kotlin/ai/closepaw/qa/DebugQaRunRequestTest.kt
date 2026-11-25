package ai.closepaw.qa

import ai.closepaw.chatgpt.control.ControlSession
import ai.closepaw.platform.virtualdisplay.MediaProjectionConsentGate
import ai.closepaw.platform.virtualdisplay.ProjectionConsentState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class DebugQaRunRequestTest {
    @Test fun confirmedModeBindsConsentActionAndRepetitionLimit() {
        val actions = mapOf(
            DebugQaMode.STRUCTURE to "ai.closepaw.qa.DIAG_USER_ARMED",
            DebugQaMode.SIX_KEY to "ai.closepaw.qa.RUN_USER_ARMED",
        )
        for ((mode, action) in actions) {
            val request = DebugQaRunRequest(mode)
            val gate = MediaProjectionConsentGate()
            val admission = DebugQaRunAdmission()
            assertEquals(DebugQaAdmissionOutcome.ACCEPTED, admission.reserve(request, captureBusy = false))
            assertTrue(gate.onRequestStarted(screenObservation = true))
            admission.waitForConsent(request, gate.generation)

            // A recreated screen has only the saved IDs and generation, and must rejoin the live reservation.
            val generation = gate.generation
            val otherMode = DebugQaMode.entries.single { it != mode }
            assertNull(admission.pendingConsent(request.id, otherMode.code, generation))
            assertNull(admission.pendingConsent(request.id, mode.code, generation + 1))
            assertNull(DebugQaRunAdmission().pendingConsent(request.id, mode.code, generation))
            val restored = checkNotNull(admission.pendingConsent(request.id, mode.code, generation))
            assertSame(request, restored)
            assertTrue(gate.onDecision(true, "android-consent") is ProjectionConsentState.Granted)
            assertFalse(admission.queue(DebugQaApprovedRun(restored, DebugQaConsent.NEW_OS_APPROVAL, generation + 1)))
            val approved = DebugQaApprovedRun(restored, DebugQaConsent.NEW_OS_APPROVAL, generation)
            assertTrue(admission.queue(approved))
            assertNull(admission.pendingConsent(request.id, mode.code, generation))
            assertEquals(DebugQaAdmissionOutcome.DENIED, admission.admit(request.id, actions.getValue(otherMode)).first)
            assertEquals(DebugQaAdmissionOutcome.ACCEPTED to approved, admission.admit(request.id, action))
            assertEquals(if (mode == DebugQaMode.STRUCTURE) 1 else 3, approved.request.mode.repetitions)
        }
    }

    @Test fun competingStartsCannotReplacePendingQueuedOrRunningRequest() {
        val admission = DebugQaRunAdmission()
        val first = DebugQaRunRequest(DebugQaMode.SIX_KEY)
        val second = DebugQaRunRequest(DebugQaMode.STRUCTURE)
        assertEquals(DebugQaAdmissionOutcome.BUSY, admission.reserve(first, captureBusy = true))
        assertEquals(DebugQaAdmissionOutcome.ACCEPTED, admission.reserve(first, captureBusy = false))
        assertEquals(DebugQaAdmissionOutcome.BUSY, admission.reserve(second, captureBusy = false))
        assertEquals(DebugQaAdmissionOutcome.DUPLICATE, admission.reserve(first, captureBusy = false))
        assertTrue(admission.queue(DebugQaApprovedRun(first, DebugQaConsent.EXISTING_OS_APPROVAL, 1)))
        assertEquals(DebugQaAdmissionOutcome.BUSY, admission.admit(second.id, second.mode.action).first)
        assertEquals(DebugQaAdmissionOutcome.ACCEPTED, admission.admit(first.id, first.mode.action).first)
        assertEquals(DebugQaAdmissionOutcome.DUPLICATE, admission.admit(first.id, first.mode.action).first)
        assertEquals(DebugQaAdmissionOutcome.BUSY, admission.reserve(second, captureBusy = false))
        assertFalse(admission.finish(second))
        assertTrue(admission.finish(first))
        assertEquals(DebugQaAdmissionOutcome.DUPLICATE, admission.admit(first.id, first.mode.action).first)
        assertEquals(DebugQaAdmissionOutcome.ACCEPTED, admission.reserve(second, captureBusy = false))
    }

    @Test fun deniedOrInterruptedApprovalCannotBecomeServiceAdmission() {
        val request = DebugQaRunRequest(DebugQaMode.SIX_KEY)
        val gate = MediaProjectionConsentGate()
        val admission = DebugQaRunAdmission()
        admission.reserve(request, captureBusy = false)
        gate.onRequestStarted(screenObservation = true)
        admission.waitForConsent(request, gate.generation)
        assertEquals(ProjectionConsentState.Denied, gate.onDecision(false, null))
        assertTrue(admission.finish(request))
        assertFalse(admission.queue(DebugQaApprovedRun(request, DebugQaConsent.NEW_OS_APPROVAL, gate.generation)))
        assertNull(admission.pendingConsent(request.id, request.mode.code, gate.generation))
        assertEquals(DebugQaAdmissionOutcome.DENIED,
            DebugQaRunAdmission().admit(request.id, request.mode.action).first)
    }

    @Test fun exportFailureIsVisibleWithoutChangingTerminalQaOutcome() {
        val request = DebugQaRunRequest(DebugQaMode.STRUCTURE, "local-grant")
        val outcome = exportDebugQaResult(request.exportGrant, { "local-grant" }) { error("storage fault") }
        assertEquals(DebugQaExportOutcome.FAILED, outcome)
        val status = DebugQaStatus(request, "DIAGNOSTIC_COMPLETE", "structural_key_evidence_captured_without_input", outcome)
        assertTrue(status.description().startsWith("DIAGNOSTIC_COMPLETE"))
        assertTrue(status.description().contains("Downloads JSON export failed"))
        assertEquals(DebugQaExportOutcome.SAVED, exportDebugQaResult("grant", { "grant" }) { true })
        assertEquals(DebugQaExportOutcome.REVOKED, exportDebugQaResult("grant", { "grant" }) { false })
        assertEquals(DebugQaExportOutcome.REVOKED, exportDebugQaResult("old-grant", { "new-grant" }) {
            fail("A new grant cannot authorize an already confirmed run")
            true
        })
        assertEquals(DebugQaExportOutcome.NOT_ENABLED, exportDebugQaResult(null, { "new-grant" }) {
            fail("Enabling export cannot change a previously confirmed run")
            true
        })
    }

    @Test fun unknownEffectStopsTheBatchAndStructuralSuccessCannotAuthorizeTyping() {
        val unknown = buildJsonObject { put("outcome", "UNKNOWN"); put("outcome_unknown", true) }
        val structural = buildJsonObject { put("outcome", "DIAGNOSTIC_COMPLETE") }
        assertEquals("UNKNOWN", DebugQaBatchPolicy.terminalState(DebugQaMode.SIX_KEY, unknown))
        assertFalse(DebugQaBatchPolicy.mayProceedAfter(unknown))
        assertEquals("DIAGNOSTIC_COMPLETE", DebugQaBatchPolicy.terminalState(DebugQaMode.STRUCTURE, structural))
        assertEquals("BLOCKED", DebugQaBatchPolicy.terminalState(DebugQaMode.SIX_KEY, structural))
    }

    @Test fun activeControlSessionBlocksLocalQaConfirmationAndLaunch() {
        assertFalse(DebugQaRunAdmission.blocksForActiveControlSession(null))
        assertFalse(DebugQaRunAdmission.blocksForActiveControlSession(ControlSession.State.IDLE))
        assertFalse(DebugQaRunAdmission.blocksForActiveControlSession(ControlSession.State.ENDED))
        assertTrue(DebugQaRunAdmission.blocksForActiveControlSession(ControlSession.State.ACTIVE))
        assertTrue(DebugQaRunAdmission.blocksForActiveControlSession(ControlSession.State.WAITING))
        assertTrue(DebugQaRunAdmission.blocksForActiveControlSession(ControlSession.State.PAUSED))
        assertTrue(DebugQaRunAdmission.blocksForActiveControlSession(ControlSession.State.REOBSERVING))
    }
}
