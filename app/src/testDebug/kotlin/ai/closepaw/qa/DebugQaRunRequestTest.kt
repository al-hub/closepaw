package ai.closepaw.qa

import ai.closepaw.chatgpt.control.ControlSession
import ai.closepaw.platform.virtualdisplay.MediaProjectionConsentGate
import ai.closepaw.platform.virtualdisplay.ProjectionConsentState
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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

    @Test fun preRunDenialFailureAndProcessRestartBuildRedactedExportableBatchSnapshot() {
        for (state in listOf("PASS", "BLOCKED", "DIAGNOSTIC_COMPLETE", "FAILED", "CANCELLED",
            "UNKNOWN", "DENIED", "BUSY", "DUPLICATE", "INTERRUPTED")) {
            assertTrue("Expected $state to be exportable", DebugQaLifecyclePolicy.isExportableTerminalState(state))
        }
        for (nonTerminal in listOf(null, "", "IN_PROGRESS", "WAITING", "RUNNING", "ARBITRARY")) {
            assertFalse("Expected $nonTerminal to be non-exportable", DebugQaLifecyclePolicy.isExportableTerminalState(nonTerminal))
        }
        assertTrue(DebugQaLifecyclePolicy.MAX_BATCH_FILE_BYTES >= 256_000L)
        assertTrue(DebugQaLifecyclePolicy.MAX_EXPORT_BYTES >= 256_000)

        val batchId = "11111111-2222-3333-4444-555555555555"
        val reqId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val snapshot = DebugQaLifecyclePolicy.buildPreRunBatchSnapshot(
            batchId = batchId,
            requestId = reqId,
            sourceCommit = "0a3e213251bae1af67a0f2f7bbbe31ee2663804d",
            versionName = "0.1.165-capturecandidate",
            versionCode = 166,
            mode = DebugQaMode.SIX_KEY,
            state = "INTERRUPTED",
            stage = "android_observation_approval",
            reason = "stale_android_consent_process_restarted",
            admissionOutcome = DebugQaAdmissionOutcome.DENIED,
            consent = null,
            candidateControlSessionState = ControlSession.State.ENDED,
            nowEpochMs = 1_795_671_263_396L,
        )
        assertEquals(3, snapshot["schema_version"]?.jsonPrimitive?.intOrNull)
        assertEquals(batchId, snapshot["batch_id"]?.jsonPrimitive?.content)
        assertEquals(reqId, snapshot["request_id"]?.jsonPrimitive?.content)
        assertEquals("0a3e213251bae1af67a0f2f7bbbe31ee2663804d", snapshot["source_commit"]?.jsonPrimitive?.content)
        assertEquals("INTERRUPTED", snapshot["state"]?.jsonPrimitive?.content)
        assertEquals("android_observation_approval", snapshot["stage"]?.jsonPrimitive?.content)
        assertEquals("stale_android_consent_process_restarted", snapshot["reason"]?.jsonPrimitive?.content)
        assertEquals("six_key_first_pass", snapshot["mode"]?.jsonPrimitive?.content)
        assertEquals("NONE", snapshot["projection_consent"]?.jsonPrimitive?.content)
        assertEquals("DENIED", snapshot["service_admission"]?.jsonPrimitive?.content)
        assertEquals("ENDED", snapshot["candidate_control_session_state"]?.jsonPrimitive?.content)
        assertEquals(false, snapshot["release_session_ended_externally_verified"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, snapshot["pre_run_terminal"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, snapshot["outcome_unknown"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(3, snapshot["repetitions_requested"]?.jsonPrimitive?.intOrNull)
        assertEquals(0, snapshot["repetitions_completed"]?.jsonPrimitive?.intOrNull)
        assertEquals(0, snapshot["runs"]?.jsonArray?.size)

        // Unsafe strings / malformed IDs / unknown state must sanitize fail-closed without leaking raw text
        val sanitized = DebugQaLifecyclePolicy.buildPreRunBatchSnapshot(
            batchId = "not-a-uuid",
            requestId = "https://secret.invalid/token",
            sourceCommit = "0a3e213251bae1af67a0f2f7bbbe31ee2663804d",
            versionName = "0.1.165-capturecandidate",
            versionCode = 166,
            mode = null,
            state = "ARBITRARY_SECRET",
            stage = "https://private.invalid/stage",
            reason = "SECRET TOKEN!",
        )
        assertEquals("FAILED", sanitized["state"]?.jsonPrimitive?.content)
        assertEquals("unavailable", sanitized["stage"]?.jsonPrimitive?.content)
        assertEquals("unavailable", sanitized["reason"]?.jsonPrimitive?.content)
        assertEquals("unknown", sanitized["mode"]?.jsonPrimitive?.content)
        assertTrue(sanitized["batch_id"]!!.jsonPrimitive.content.matches(Regex("[a-f0-9-]{36}")))
        assertTrue(sanitized["request_id"]!!.jsonPrimitive.content.matches(Regex("[a-f0-9-]{36}")))
        val serialized = sanitized.toString()
        assertFalse(serialized.contains("secret.invalid"))
        assertFalse(serialized.contains("private.invalid"))
        assertFalse(serialized.contains("SECRET TOKEN"))

        // Verify admission.current stays non-null (isBusy == true) across reserve -> waitForConsent -> queue -> admit -> finish
        val admission = DebugQaRunAdmission()
        val req = DebugQaRunRequest(DebugQaMode.SIX_KEY)
        assertNull(admission.current)
        assertEquals(DebugQaAdmissionOutcome.ACCEPTED, admission.reserve(req, captureBusy = false))
        assertNotNull(admission.current)
        admission.waitForConsent(req, 42L)
        assertNotNull(admission.current)
        assertTrue(admission.queue(DebugQaApprovedRun(req, DebugQaConsent.NEW_OS_APPROVAL, 42L)))
        assertNotNull(admission.current)
        assertEquals(DebugQaAdmissionOutcome.ACCEPTED, admission.admit(req.id, req.mode.action).first)
        assertNotNull(admission.current)
        assertTrue(admission.finish(req))
        assertNull(admission.current)
    }
}
