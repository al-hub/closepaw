package ai.closepaw.qa

import ai.closepaw.chatgpt.control.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * #147 P0/P1 DebugSixKeyExecution, shared cap-3 budget, initial 4-key certification refresh,
 * redacted diagnostics, 3x PASS-only batch policy, and single-device-clock timeline tests.
 */
class DebugSixKeyRealSessionRecoveryTest {
    private val chrome = "com.android.chrome"
    private val samsungIme = "com.samsung.android.honeyboard"
    private val ime = ControlKeyboard(20, samsungIme, "$samsungIme/.service.HoneyBoardService", true, true)

    private fun editor(
        text: String = "",
        sel: Int = text.length,
        focusable: Boolean = true,
        cursorGen: Long? = 10L,
    ) = ControlNode(
        id = 1, text = text, resourceId = "$chrome:id/url_bar",
        clickable = true, editable = true, enabled = true, empty = text.isEmpty(),
        bounds = "0 100 1080 220", className = "android.widget.EditText",
        focused = true, focusable = focusable, selectionStart = sel, selectionEnd = sel,
        sourceWindowId = 10, sourcePackage = chrome, nativeEditorId = "chrome-url-1",
        keyboardCursorGeneration = cursorGen,
    )

    private fun hangulNodes(): List<ControlNode> {
        val row1 = listOf("ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ", "ㅛ", "ㅕ", "ㅑ")
        val row2 = listOf("ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ")
        val keys1 = row1.mapIndexed { i, label ->
            val left = 20 + i * 120
            ControlNode(
                id = 101 + i, text = label, description = label,
                bounds = "$left 1600 ${left + 100} 1720", parent = 500,
                className = "android.widget.Button", enabled = true, clickable = true,
                sourceWindowId = 20, sourcePackage = samsungIme,
                keyboardKey = label, visualKeyCandidate = true, textEntryKey = true,
                visualKeyChildCount = 0,
            )
        }
        val keys2 = row2.mapIndexed { i, label ->
            val left = 20 + i * 120
            ControlNode(
                id = 121 + i, text = label, description = label,
                bounds = "$left 1750 ${left + 100} 1870", parent = 501,
                className = "android.widget.Button", enabled = true, clickable = true,
                sourceWindowId = 20, sourcePackage = samsungIme,
                keyboardKey = label, visualKeyCandidate = true, textEntryKey = true,
                visualKeyChildCount = 0,
            )
        }
        val space = ControlNode(
            id = 150, bounds = "300 2000 780 2120", parent = 502,
            className = "android.view.View", enabled = true, clickable = true,
            sourceWindowId = 20, sourcePackage = samsungIme,
            keyboardImageCandidate = true, visualKeyChildCount = 0,
        )
        return keys1 + keys2 + space
    }

    private fun latinNodes(): List<ControlNode> {
        val row1 = listOf("q", "w", "e", "r", "t", "y", "u", "i")
        val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k")
        val keys1 = row1.mapIndexed { i, label ->
            val left = 20 + i * 120
            ControlNode(
                id = 201 + i, text = label, description = label,
                bounds = "$left 1600 ${left + 100} 1720", parent = 600,
                className = "android.widget.Button", enabled = true, clickable = true,
                sourceWindowId = 20, sourcePackage = samsungIme,
                keyboardKey = label, visualKeyCandidate = true, textEntryKey = true,
                visualKeyChildCount = 0,
            )
        }
        val keys2 = row2.mapIndexed { i, label ->
            val left = 20 + i * 120
            ControlNode(
                id = 221 + i, text = label, description = label,
                bounds = "$left 1750 ${left + 100} 1870", parent = 601,
                className = "android.widget.Button", enabled = true, clickable = true,
                sourceWindowId = 20, sourcePackage = samsungIme,
                keyboardKey = label, visualKeyCandidate = true, textEntryKey = true,
                visualKeyChildCount = 0,
            )
        }
        val switchKey = ControlNode(
            id = 250, text = "", description = "한국어로 변경",
            bounds = "120 2000 260 2120", parent = 602,
            className = "android.view.ViewGroup", enabled = true, clickable = false,
            sourceWindowId = 20, sourcePackage = samsungIme,
            keyboardKey = "한/영", visualKeyCandidate = true, textEntryKey = false,
            visualKeyChildCount = 1,
        )
        return keys1 + keys2 + switchKey
    }

    private fun hangulFrame(
        fp: String = "fp-ko-1",
        text: String = "",
        sel: Int = text.length,
        touchContext: String = "rot0-w10-w20",
        reason: String? = null,
    ): ControlFrame {
        val ed = editor(text = text, sel = sel)
        return ControlFrame(
            packageName = chrome, windowId = 10, foregroundWindowId = 10,
            fingerprint = fp, nodes = listOf(ed) + hangulNodes(),
            reason = reason, touchContext = touchContext, windowContext = "wctx-1",
            configurationRevision = 1L, keyboard = ime, inputTargetId = ed.id, inputTarget = ed,
        )
    }

    private fun latinFrame(fp: String = "fp-en-1"): ControlFrame {
        val ed = editor()
        return ControlFrame(
            packageName = chrome, windowId = 10, foregroundWindowId = 10,
            fingerprint = fp, nodes = listOf(ed) + latinNodes(),
            touchContext = "rot0-w10-w20", windowContext = "wctx-1",
            configurationRevision = 1L, keyboard = ime, inputTargetId = ed.id, inputTarget = ed,
        )
    }

    private fun obsData(obsId: String, visId: String): JsonObject = buildJsonObject {
        put("observation_id", obsId)
        put("security_scan_complete", true)
        put("output_truncated", false)
        put("visual", buildJsonObject { put("scope", "keyboard"); put("visual_id", visId) })
        put("keyboard_preflight", buildJsonObject {
            put("status", "REVIEW_READY")
            put("reason_code", "image_rect_review_required")
            put("effective_empty_caret_zero_verified", true)
        })
        put("nodes", buildJsonArray {
            for (n in hangulNodes().filter { it.keyboardKey in setOf("ㄱ", "ㅏ", "ㄴ") || it.keyboardImageCandidate }) {
                add(buildJsonObject {
                    put("node_id", n.id); put("enabled", true); put("bounds", n.bounds)
                    put("keyboard_key", n.keyboardKey)
                    put("visual_key_candidate", n.visualKeyCandidate)
                    put("keyboard_image_candidate", n.keyboardImageCandidate)
                })
            }
        })
    }

    private fun sampleJournal(): JsonObject = buildJsonObject {
        put("elapsed_ms", 2249)
        put("unrecorded_count", 0)
        put("events", buildJsonArray {
            add(buildJsonObject {
                put("type", "windows_changed")
                put("reported_owner", "unknown")
                put("event_window_match", "other")
                put("event_display_class", "default")
                put("after_action_source_check_ms", 812)
                put("window_change_mask", 8)
                put("unknown_change_bits", false)
            })
        })
    }

    @Test
    fun initialFourKeyCertificationDisplayWindowChanged_discardsAllFourAndRecertifiesFresh() = runBlocking {
        var observeCount = 0
        val certifiedVisualIds = mutableListOf<String>()
        val dispatchedVisualIds = mutableListOf<String>()
        val dispatchedNonces = mutableListOf<String>()
        var invalidations = 0
        var captureCount = 0
        var nonceSeq = 0

        val outcome = DebugSixKeyExecution.runWithSeam(
            captureFrame = {
                captureCount++
                when (captureCount) {
                    1 -> hangulFrame("fp-1")
                    2 -> hangulFrame("fp-2") // recovery recapture
                    else -> hangulFrame("fp-final", text = "가 나 ", sel = 4)
                }
            },
            observeSequence = {
                observeCount++
                DebugSixKeyExecution.ObservePacket(
                    success = true,
                    data = obsData("obs-$observeCount", "vis-$observeCount"),
                    certifiedFrame = hangulFrame("fp-obs-$observeCount"),
                )
            },
            certifyCandidate = { visualId, _, candidate, _ ->
                certifiedVisualIds += "$visualId:${candidate.label}"
                if (visualId == "vis-1" && candidate.label == "ㄴ") {
                    PixelCertificationOutcome(
                        crop = null, method = "none", failureStage = "proof_display_window_changed",
                        visualSourceFailure = VisualSourceFailure(
                            VisualSourceStage.ACTION_VALIDATION,
                            VisualSourceCondition.DISPLAY_WINDOW_CHANGED,
                            sampleJournal(),
                        ),
                        captureWindowChanges = sampleJournal(),
                    )
                } else {
                    PixelCertificationOutcome(
                        crop = TapBounds(10, 10, 50, 50),
                        method = if (candidate.imageOnly) "space_glyph" else "native_structural_and_live_surface",
                    )
                }
            },
            dispatchSequence = { payload, proof ->
                dispatchedVisualIds += payload["visual_id"]!!.jsonPrimitive.content
                dispatchedNonces += proof.nonce
                DebugSixKeyExecution.SequencePacket(
                    success = true,
                    data = buildJsonObject {
                        put("action_attempted", true); put("outcome_unknown", false)
                        put("app_result_verified", true); put("completed_keys", 6)
                    },
                )
            },
            sessionId = "sess-1", sessionEpoch = 1L, first = hangulFrame("fp-first"),
            allowed = { true }, checkpoint = { _, _ -> },
            nonceGenerator = { "nonce-${++nonceSeq}" },
            invalidateObservation = { invalidations++ },
        )

        assertNull("Expected PASS after full 4-key refresh, got ${outcome.failure}", outcome.failure)
        assertEquals(2, observeCount)
        assertEquals(1, invalidations)
        assertEquals(1, outcome.recoveryAttempts)
        assertEquals(listOf("vis-2"), dispatchedVisualIds)
        assertEquals(listOf("nonce-1"), dispatchedNonces)
        // All 4 keys freshly certified against vis-2 (none reused from vis-1)
        assertEquals(
            listOf("vis-1:ㄱ", "vis-1:ㅏ", "vis-1:ㄴ", "vis-2:ㄱ", "vis-2:ㅏ", "vis-2:ㄴ", "vis-2:Space"),
            certifiedVisualIds,
        )
        // Redacted window-change journal attached to the failed certification evidence
        val failedCertEv = outcome.evidence.first {
            it["source"]?.jsonPrimitive?.content == "autonomous_keyboard_certification_check" &&
                it["all_candidates_certified"]?.jsonPrimitive?.boolean == false
        }
        assertEquals("action_validation", failedCertEv["visual_source_stage"]?.jsonPrimitive?.content)
        assertEquals("display_window_changed", failedCertEv["visual_source_condition"]?.jsonPrimitive?.content)
        assertNotNull(failedCertEv["capture_window_changes"])
    }

    @Test
    fun code168ExactSequence_layoutSwitchHitsDisplayChange_ActionValidation_andOneSidedCursorRace_recoversWithinCap3() = runBlocking {
        // Replays the EXACT code168 device failure chain (Attempts 0, 1, 2) + succeeds on attempt 3!
        var switchCalls = 0
        var captureCalls = 0
        val budget = SharedQaRecoveryBudget(maxAttempts = 3)
        val recoverableMismatch = KeyboardContextMismatch(
            kind = KeyboardContextMismatchKind.CURSOR_PROOF_CHANGED,
            location = CursorRaceLocation.AFTER,
            beforeFailure = null,
            afterFailure = CursorProofFailure.SCREEN_CHANGE_RACE,
            recoverableReadOnlyCursorRace = true,
        )

        val outcome = DebugSixKeyExecution.runWithSeam(
            captureFrame = {
                captureCalls++
                when (captureCalls) {
                    1, 2, 3, 4 -> latinFrame("fp-en-$captureCalls")
                    5 -> hangulFrame("fp-ko-after-switch")
                    else -> hangulFrame("fp-ko-final", text = "가 나 ", sel = 4)
                }
            },
            observeSequence = {
                DebugSixKeyExecution.ObservePacket(
                    success = true, data = obsData("obs-1", "vis-1"), certifiedFrame = hangulFrame("fp-ko-obs"),
                )
            },
            certifyCandidate = { _, _, candidate, _ ->
                PixelCertificationOutcome(
                    crop = TapBounds(10, 10, 50, 50),
                    method = if (candidate.imageOnly) "space_glyph" else "native_structural_and_live_surface",
                )
            },
            dispatchSequence = { _, _ ->
                DebugSixKeyExecution.SequencePacket(
                    success = true,
                    data = buildJsonObject {
                        put("action_attempted", true); put("outcome_unknown", false)
                        put("app_result_verified", true); put("completed_keys", 6)
                    },
                )
            },
            sessionId = "sess-code168", sessionEpoch = 1L, first = latinFrame("fp-en-0"),
            allowed = { true }, checkpoint = { _, _ -> },
            switchToKoreanLayout = { _, _, _ ->
                switchCalls++
                when (switchCalls) {
                    1 -> DebugSixKeyExecution.LayoutSwitchPacket(
                        success = false, attempted = false, unknown = false,
                        reason = "display_window_changed_during_capture",
                        captureWindowChanges = sampleJournal(),
                    )
                    2 -> DebugSixKeyExecution.LayoutSwitchPacket(
                        success = false, attempted = false, unknown = false,
                        reason = VISUAL_SOURCE_REFUSED,
                        visualSourceStage = VisualSourceStage.ACTION_VALIDATION.code,
                        visualSourceCondition = VisualSourceCondition.DISPLAY_WINDOW_CHANGED.code,
                        captureWindowChanges = sampleJournal(),
                    )
                    3 -> DebugSixKeyExecution.LayoutSwitchPacket(
                        success = false, attempted = false, unknown = false,
                        reason = "visual_post_keyboard_context_changed",
                        cursorProofMismatch = recoverableMismatch,
                    )
                    else -> DebugSixKeyExecution.LayoutSwitchPacket(success = true, attempted = true, unknown = false)
                }
            },
            recoveryBudget = budget,
        )

        assertNull("Expected full PASS after recovering all 3 code168 races, got ${outcome.failure}", outcome.failure)
        assertEquals(4, switchCalls)
        assertEquals(3, outcome.recoveryAttempts)
        assertEquals(3, budget.used)
        assertTrue(outcome.layoutSwitchConfirmed)
        assertEquals(6, outcome.progress.verified)

        // Verify ALL 3 failed layout switch evidence entries carry redacted provenance!
        val layoutEvents = outcome.evidence.filter { it["source"]?.jsonPrimitive?.content == "korean_layout_readiness" }
        assertNotNull(layoutEvents[0]["capture_window_changes"])
        assertEquals("action_validation", layoutEvents[1]["visual_source_stage"]?.jsonPrimitive?.content)
        assertEquals("display_window_changed", layoutEvents[1]["visual_source_condition"]?.jsonPrimitive?.content)
        assertNotNull(layoutEvents[1]["capture_window_changes"])
        val mismatchJson = layoutEvents[2]["cursor_proof_mismatch"]?.jsonObject
        assertNotNull(mismatchJson)
        assertEquals("cursor_proof_changed", mismatchJson!!["kind"]?.jsonPrimitive?.content)
        assertEquals("after", mismatchJson["location"]?.jsonPrimitive?.content)
        assertEquals("screen_change_race", mismatchJson["after_cause"]?.jsonPrimitive?.content)
        assertEquals(true, mismatchJson["recoverable"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun nonRecoverableCursorMismatchAndPreDispatchFourthAttemptAndSafetyGates_failClosed() = runBlocking {
        // 1. IPC_NULL cursor mismatch MUST NOT recover (0 retries)
        val ipcMismatch = KeyboardContextMismatch(
            kind = KeyboardContextMismatchKind.CURSOR_PROOF_CHANGED,
            location = CursorRaceLocation.AFTER,
            beforeFailure = null,
            afterFailure = CursorProofFailure.IPC_NULL,
            recoverableReadOnlyCursorRace = false,
        )
        val ipcOutcome = DebugSixKeyExecution.runWithSeam(
            captureFrame = { latinFrame("fp-en") },
            observeSequence = { error("must not reach") },
            certifyCandidate = { _, _, _, _ -> error("must not reach") },
            dispatchSequence = { _, _ -> error("must not reach") },
            sessionId = "sess-1", sessionEpoch = 1L, first = latinFrame("fp-en"),
            allowed = { true }, checkpoint = { _, _ -> },
            switchToKoreanLayout = { _, _, _ ->
                DebugSixKeyExecution.LayoutSwitchPacket(
                    success = false, attempted = false, unknown = false,
                    reason = "visual_post_keyboard_context_changed",
                    cursorProofMismatch = ipcMismatch,
                )
            },
        )
        assertEquals("visual_post_keyboard_context_changed", ipcOutcome.failure)
        assertEquals(0, ipcOutcome.recoveryAttempts)

        // 2. Shared budget caps total recoveries across recapture + observe + dispatch strictly at 3
        var cap = 0
        val sharedBudget = SharedQaRecoveryBudget(maxAttempts = 3)
        val capOutcome = DebugSixKeyExecution.runWithSeam(
            captureFrame = {
                cap++
                when (cap) {
                    1 -> hangulFrame("fp-1")
                    2 -> hangulFrame("fp-recap-fail", reason = "secure_window_changed") // consumes 2nd budget slot!
                    else -> hangulFrame("fp-recovered-$cap")
                }
            },
            observeSequence = {
                DebugSixKeyExecution.ObservePacket(
                    success = false,
                    data = buildJsonObject { put("reason_code", "display_window_changed_during_capture") },
                )
            },
            certifyCandidate = { _, _, _, _ -> error("must not reach") },
            dispatchSequence = { _, _ -> error("must not reach") },
            sessionId = "sess-2", sessionEpoch = 1L, first = hangulFrame("fp-0"),
            allowed = { true }, checkpoint = { _, _ -> },
            recoveryBudget = sharedBudget,
        )
        assertEquals("keyboard_observation_refused", capOutcome.failure)
        assertEquals(3, sharedBudget.used)
        assertEquals(3, capOutcome.recoveryAttempts)

        // 3. Rotation (touchContext change) during recovery recertification fails closed
        var rotCap = 0
        val rotOutcome = DebugSixKeyExecution.runWithSeam(
            captureFrame = {
                rotCap++
                if (rotCap == 1) hangulFrame("fp-1", touchContext = "rot0-w10-w20")
                else hangulFrame("fp-2", touchContext = "rot1-landscape")
            },
            observeSequence = {
                DebugSixKeyExecution.ObservePacket(
                    success = false,
                    data = buildJsonObject { put("reason_code", "display_window_changed_during_capture") },
                )
            },
            certifyCandidate = { _, _, _, _ -> error("must not reach") },
            dispatchSequence = { _, _ -> error("must not reach") },
            sessionId = "sess-3", sessionEpoch = 1L, first = hangulFrame("fp-0"),
            allowed = { true }, checkpoint = { _, _ -> },
        )
        assertEquals("touch_context_or_rotation_changed", rotOutcome.failure)
        assertEquals(1, rotOutcome.recoveryAttempts)

        // 4. Overlay (display_unowned_window) fails closed immediately with 0 retries
        val overlayOutcome = DebugSixKeyExecution.runWithSeam(
            captureFrame = { hangulFrame("fp-1") },
            observeSequence = {
                DebugSixKeyExecution.ObservePacket(
                    success = false,
                    data = buildJsonObject { put("reason_code", "display_surface_unverified") },
                    displayPlanRefusal = "display_unowned_window",
                )
            },
            certifyCandidate = { _, _, _, _ -> error("must not reach") },
            dispatchSequence = { _, _ -> error("must not reach") },
            sessionId = "sess-4", sessionEpoch = 1L, first = hangulFrame("fp-0"),
            allowed = { true }, checkpoint = { _, _ -> },
        )
        assertEquals("keyboard_observation_refused", overlayOutcome.failure)
        assertEquals(0, overlayOutcome.recoveryAttempts)
    }

    @Test
    fun batchPolicyRequiresExactFinalTextAndCaret_andLifecycleTimelineUsesSingleDeviceClockOnly() {
        val fullPass = buildJsonObject {
            put("outcome", "PASS")
            put("goal_verified", true)
            put("completed_keys", 6)
            put("touch_attempts", 6)
            put("outcome_unknown", false)
            put("final_text", "가 나 ")
            put("final_caret_start", 4)
            put("final_caret_end", 4)
        }
        assertTrue(DebugQaBatchPolicy.mayProceedAfter(fullPass))
        assertEquals("PASS", DebugQaBatchPolicy.terminalState(DebugQaMode.SIX_KEY, fullPass))

        // Missing or mismatched final_text / caret MUST NOT proceed to repetition #2/#3
        assertFalse(DebugQaBatchPolicy.mayProceedAfter(buildJsonObject {
            fullPass.forEach { (k, v) -> if (k != "final_text") put(k, v) }
            put("final_text", "가 나")
        }))
        assertFalse(DebugQaBatchPolicy.mayProceedAfter(buildJsonObject {
            fullPass.forEach { (k, v) -> if (k != "final_caret_end") put(k, v) }
            put("final_caret_end", 3)
        }))
        assertFalse(DebugQaBatchPolicy.mayProceedAfter(buildJsonObject {
            fullPass.forEach { (k, v) -> if (k != "outcome_unknown") put(k, v) }
            put("outcome_unknown", true)
        }))

        // Single-device-clock lifecycle timeline never fabricates external Release end timestamps
        val timeline = DebugOneStopQa.lifecycleTimelineEvidence(
            confirmedEpochMs = 1_795_700_000_000L,
            confirmedElapsedMs = 10_000L,
            approvedEpochMs = 1_795_700_001_500L,
            approvedElapsedMs = 11_500L,
            runEntryEpochMs = 1_795_700_002_000L,
            runEntryElapsedMs = 12_000L,
            awaitChromeEpochMs = 1_795_700_003_200L,
            awaitChromeElapsedMs = 13_200L,
            sessionStartedEpochMs = 1_795_700_003_400L,
            sessionStartedElapsedMs = 13_400L,
            endedEpochMs = 1_795_700_010_000L,
            endedElapsedMs = 20_000L,
        )
        assertEquals("android_epoch_ms_plus_elapsed_realtime_ms", timeline["clock_domain"]?.jsonPrimitive?.content)
        assertEquals(10_000L, timeline["request_confirmed"]!!.jsonObject["elapsed_realtime_ms"]?.jsonPrimitive?.long)
        assertEquals(11_500L, timeline["approval_completed"]!!.jsonObject["elapsed_realtime_ms"]?.jsonPrimitive?.long)
        assertEquals(12_000L, timeline["run_entry"]!!.jsonObject["elapsed_realtime_ms"]?.jsonPrimitive?.long)
        assertEquals(13_200L, timeline["await_chrome_completed"]!!.jsonObject["elapsed_realtime_ms"]?.jsonPrimitive?.long)
        assertEquals(13_400L, timeline["session_started"]!!.jsonObject["elapsed_realtime_ms"]?.jsonPrimitive?.long)
        assertEquals(20_000L, timeline["ended"]!!.jsonObject["elapsed_realtime_ms"]?.jsonPrimitive?.long)
        assertFalse(timeline.toString().contains("release_session_ended"))
    }
}
