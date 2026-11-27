package ai.closepaw.qa

import ai.closepaw.chatgpt.control.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
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

    @Test
    fun code169ExactChronology_passesAt15000msAndRecordsElapsedBudgetRejectionAt15001ms() = runBlocking {
        // Case A: Exact code169 chronology within 15_000ms (2 recoveries in switch + 1 recovery on first 4-key observe) -> PASS!
        var nowPass = 10_000L
        val passBudget = SharedQaRecoveryBudget(clockMs = { nowPass })
        val passFrames = ArrayDeque(listOf(
            latinFrame("fp-1"),
            latinFrame("fp-2"),
            latinFrame("fp-3"),
            hangulFrame("fp-4"),
            hangulFrame("fp-5"),
            hangulFrame("fp-final", text = "가 나 ", sel = 4),
        ))
        var switchCalls = 0
        var obsCalls = 0
        val passOut = DebugSixKeyExecution.runWithSeam(
            captureFrame = { passFrames.removeFirst() },
            observeSequence = {
                obsCalls++
                if (obsCalls == 1) {
                    nowPass = 10_000L + 15_000L // exact 15_000ms boundary -> 3rd claim ALLOWED!
                    DebugSixKeyExecution.ObservePacket(
                        success = false,
                        data = buildJsonObject {
                            put("reason_code", "display_window_changed_during_capture")
                            put("action_attempted", false); put("outcome_unknown", false)
                        },
                    )
                } else {
                    DebugSixKeyExecution.ObservePacket(true, obsData("obs-1", "vis-1"), certifiedFrame = hangulFrame("fp-5"))
                }
            },
            certifyCandidate = { _, _, cand, _ ->
                PixelCertificationOutcome(
                    crop = TapBounds(10, 10, 80, 80),
                    method = if (cand.imageOnly) "space_glyph" else "native_structural_and_live_surface",
                )
            },
            dispatchSequence = { _, _ ->
                DebugSixKeyExecution.SequencePacket(true, buildJsonObject {
                    put("action_attempted", true); put("outcome_unknown", false)
                    put("app_result_verified", true); put("completed_keys", 6)
                })
            },
            sessionId = "s-pass", sessionEpoch = 1L, first = latinFrame("fp-0"),
            allowed = { true }, checkpoint = { _, _ -> }, clockMs = { nowPass },
            switchToKoreanLayout = { _, _, _ ->
                switchCalls++
                when (switchCalls) {
                    1 -> {
                        nowPass += 4_000L
                        DebugSixKeyExecution.LayoutSwitchPacket(false, false, false, "display_window_changed_during_capture")
                    }
                    2 -> {
                        nowPass += 4_000L
                        DebugSixKeyExecution.LayoutSwitchPacket(
                            false, false, false, VISUAL_SOURCE_REFUSED,
                            visualSourceStage = VisualSourceStage.NATIVE_ACTION_VALIDATION.code,
                            visualSourceCondition = VisualSourceCondition.DISPLAY_WINDOW_CHANGED.code,
                        )
                    }
                    else -> DebugSixKeyExecution.LayoutSwitchPacket(true, true, false)
                }
            },
            recoveryBudget = passBudget,
        )
        assertNull("Expected PASS at exact 15_000ms boundary, got ${passOut.failure}", passOut.failure)
        assertEquals(3, passOut.recoveryAttempts)
        assertEquals(1, passOut.layoutSwitchAttempts)
        assertTrue(passOut.layoutSwitchConfirmed)
        assertEquals(6, passOut.progress.verified)

        // Case B: Exact code169 chronology at 15_001ms -> 3rd claim REJECTED with structured elapsed_budget telemetry!
        var nowFail = 10_000L
        val failBudget = SharedQaRecoveryBudget(clockMs = { nowFail })
        val failFrames = ArrayDeque(listOf(
            latinFrame("fp-1"), latinFrame("fp-2"), latinFrame("fp-3"), hangulFrame("fp-4"),
        ))
        var failSwitchCalls = 0
        val failOut = DebugSixKeyExecution.runWithSeam(
            captureFrame = { failFrames.removeFirst() },
            observeSequence = {
                nowFail = 10_000L + 15_001L // 1ms past 15s window!
                DebugSixKeyExecution.ObservePacket(
                    success = false,
                    data = buildJsonObject {
                        put("reason_code", "display_window_changed_during_capture")
                        put("action_attempted", false); put("outcome_unknown", false)
                    },
                )
            },
            certifyCandidate = { _, _, _, _ -> error("not reached") },
            dispatchSequence = { _, _ -> error("not reached") },
            sessionId = "s-fail", sessionEpoch = 1L, first = latinFrame("fp-0"),
            allowed = { true }, checkpoint = { _, _ -> }, clockMs = { nowFail },
            switchToKoreanLayout = { _, _, _ ->
                failSwitchCalls++
                when (failSwitchCalls) {
                    1 -> DebugSixKeyExecution.LayoutSwitchPacket(false, false, false, "display_window_changed_during_capture")
                    2 -> DebugSixKeyExecution.LayoutSwitchPacket(
                        false, false, false, VISUAL_SOURCE_REFUSED,
                        visualSourceStage = VisualSourceStage.NATIVE_ACTION_VALIDATION.code,
                        visualSourceCondition = VisualSourceCondition.DISPLAY_WINDOW_CHANGED.code,
                    )
                    else -> DebugSixKeyExecution.LayoutSwitchPacket(true, true, false)
                }
            },
            recoveryBudget = failBudget,
        )
        assertEquals("keyboard_observation_refused", failOut.failure)
        assertEquals(2, failOut.recoveryAttempts)
        assertFalse(failOut.progress.uncertain)
        val rejectedDecision = failOut.evidence.last { it["source"]?.jsonPrimitive?.contentOrNull == "recovery_decision" }
        assertEquals("sequence_observe", rejectedDecision["phase"]?.jsonPrimitive?.content)
        assertEquals(false, rejectedDecision["allowed"]?.jsonPrimitive?.boolean)
        assertEquals("elapsed_budget", rejectedDecision["rejection_reason"]?.jsonPrimitive?.content)
        assertEquals(2, rejectedDecision["budget_used"]?.jsonPrimitive?.int)
        assertEquals(3, rejectedDecision["budget_max"]?.jsonPrimitive?.int)
        assertEquals(15_001L, rejectedDecision["budget_elapsed_ms"]?.jsonPrimitive?.long)
        assertEquals(false, rejectedDecision["within_window"]?.jsonPrimitive?.boolean)
        assertEquals(true, rejectedDecision["authority_active"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun windowEventIntervalParsedFromVisualSourceFailure_andControlOverlayPreservedBySanitizer() = runBlocking {
        val journal = buildJsonObject {
            put("elapsed_ms", 9L)
            put("unrecorded_count", 0L)
            put("events", buildJsonArray {
                add(buildJsonObject {
                    put("after_action_source_check_ms", 3L)
                    put("type", "windows_changed")
                    put("reported_owner", "unknown")
                    put("event_window_match", "control_overlay")
                    put("window_change_mask", 8)
                    put("unknown_change_bits", false)
                    put("event_display_class", "default")
                })
            })
        }
        val srcFailure = VisualSourceFailure(
            VisualSourceStage.PRE_GESTURE,
            VisualSourceCondition.DISPLAY_WINDOW_CHANGED,
            windowEvents = journal,
        ).json()
        val f0 = hangulFrame("fp-0")
        val out = DebugSixKeyExecution.runWithSeam(
            captureFrame = { f0 },
            observeSequence = { DebugSixKeyExecution.ObservePacket(true, obsData("obs-1", "vis-1"), certifiedFrame = f0) },
            certifyCandidate = { _, _, cand, _ ->
                PixelCertificationOutcome(
                    crop = TapBounds(10, 10, 80, 80),
                    method = if (cand.imageOnly) "space_glyph" else "native_structural_and_live_surface",
                )
            },
            dispatchSequence = { _, _ ->
                DebugSixKeyExecution.SequencePacket(
                    success = false,
                    data = buildJsonObject {
                        put("reason_code", VISUAL_SOURCE_REFUSED)
                        put("action_attempted", false); put("outcome_unknown", false)
                        put("completed_keys", 0); put("visual_source_failure", srcFailure)
                    },
                    captureWindowChanges = null,
                )
            },
            sessionId = "s-interval", sessionEpoch = 1L, first = f0,
            allowed = { true }, checkpoint = { _, _ -> },
        )
        assertEquals(VISUAL_SOURCE_REFUSED, out.failure)
        assertEquals(0, out.recoveryAttempts) // PRE_GESTURE never retried!
        val seqEv = out.evidence.first { it["source"]?.jsonPrimitive?.contentOrNull == "visual_key_sequence_result" }
        val changes = seqEv["capture_window_changes"]?.jsonObject
        assertNotNull("Must parse window_event_interval from VisualSourceFailure.json()", changes)
        val ev0 = changes!!["events"]!!.jsonArray.single().jsonObject
        assertEquals("control_overlay", ev0["event_window_match"]?.jsonPrimitive?.content)
        assertEquals(8, ev0["window_change_mask"]?.jsonPrimitive?.int)
    }

    @Test
    fun postconditionReadOnlyReobservationRecoversWithoutReplayingActions_andFailsClosedIfPersistent() = runBlocking {
        // 1. Transient screen_changed on afterSwitch (2x) and final (2x) recovers with 0 touch replay & 0 budget used!
        var switchDispatches = 0
        var seqDispatches = 0
        val frames = ArrayDeque(listOf(
            latinFrame("fp-1"),
            hangulFrame("fp-sw-err1", reason = "screen_changed"),
            hangulFrame("fp-sw-err2", reason = "screen_changed"),
            hangulFrame("fp-ko"),
            hangulFrame("fp-fin-err1", text = "가 나 ", sel = 4, reason = "screen_changed"),
            hangulFrame("fp-fin-err2", text = "가 나 ", sel = 4, reason = "screen_changed"),
            hangulFrame("fp-fin-ok", text = "가 나 ", sel = 4),
        ))
        val out = DebugSixKeyExecution.runWithSeam(
            captureFrame = { frames.removeFirst() },
            observeSequence = { DebugSixKeyExecution.ObservePacket(true, obsData("obs-1", "vis-1"), certifiedFrame = hangulFrame("fp-ko")) },
            certifyCandidate = { _, _, cand, _ ->
                PixelCertificationOutcome(
                    crop = TapBounds(10, 10, 80, 80),
                    method = if (cand.imageOnly) "space_glyph" else "native_structural_and_live_surface",
                )
            },
            dispatchSequence = { _, _ ->
                seqDispatches++
                DebugSixKeyExecution.SequencePacket(true, buildJsonObject {
                    put("action_attempted", true); put("outcome_unknown", false)
                    put("app_result_verified", true); put("completed_keys", 6)
                })
            },
            sessionId = "s-post-ok", sessionEpoch = 1L, first = latinFrame("fp-0"),
            allowed = { true }, checkpoint = { _, _ -> },
            switchToKoreanLayout = { _, _, _ ->
                switchDispatches++
                DebugSixKeyExecution.LayoutSwitchPacket(true, true, false)
            },
        )
        assertNull("Expected PASS via passive postcondition re-observation, got ${out.failure}", out.failure)
        assertEquals(1, switchDispatches)
        assertEquals(1, seqDispatches)
        assertEquals(0, out.recoveryAttempts) // Passive postcondition does NOT consume pre-dispatch budget
        assertFalse(out.progress.uncertain)

        // 2. Persistent screen_changed (>2 retries) on final capture stays strictly fail-closed UNKNOWN!
        val failFrames = ArrayDeque(listOf(
            hangulFrame("fp-ko"),
            hangulFrame("fp-e1", text = "가 나 ", sel = 4, reason = "screen_changed"),
            hangulFrame("fp-e2", text = "가 나 ", sel = 4, reason = "screen_changed"),
            hangulFrame("fp-e3", text = "가 나 ", sel = 4, reason = "screen_changed"),
        ))
        var failSeqDispatches = 0
        val failOut = DebugSixKeyExecution.runWithSeam(
            captureFrame = { failFrames.removeFirst() },
            observeSequence = { DebugSixKeyExecution.ObservePacket(true, obsData("obs-1", "vis-1"), certifiedFrame = hangulFrame("fp-ko")) },
            certifyCandidate = { _, _, cand, _ ->
                PixelCertificationOutcome(
                    crop = TapBounds(10, 10, 80, 80),
                    method = if (cand.imageOnly) "space_glyph" else "native_structural_and_live_surface",
                )
            },
            dispatchSequence = { _, _ ->
                failSeqDispatches++
                DebugSixKeyExecution.SequencePacket(true, buildJsonObject {
                    put("action_attempted", true); put("outcome_unknown", false)
                    put("app_result_verified", true); put("completed_keys", 6)
                })
            },
            sessionId = "s-post-fail", sessionEpoch = 1L, first = hangulFrame("fp-0"),
            allowed = { true }, checkpoint = { _, _ -> },
        )
        assertEquals("six_key_final_read_unconfirmed", failOut.failure)
        assertTrue(failOut.progress.uncertain)
        assertEquals(1, failSeqDispatches) // Zero replay!
    }

    @Test
    fun repetition2NarrowPriorBenchmarkOmniboxGate_allowsExactGaNaOnlyAndRefusesArbitraryDrafts() = runBlocking {
        val chromeRec = ControlWindowRecord(
            ControlWindow(10, 0, 0, true, true, true, chrome, hasRoot = true),
            ControlWindowRecord.TYPE_APPLICATION,
            ControlWindowRect(0, 0, 1080, 2400),
        )
        val imeRec = ControlWindowRecord(
            ControlWindow(20, 0, 1, false, false, false, "", hasRoot = false),
            ControlWindowRecord.TYPE_INPUT_METHOD,
            ControlWindowRect(0, 1500, 1080, 2400),
        )
        val snapWithIme = ControlWindowSnapshot(10, 10, 2, listOf(chromeRec, imeRec))
        val snapNoIme = ControlWindowSnapshot(10, 10, 1, listOf(chromeRec))
        val rep1ResidualBar = editor(text = "가 나 ", sel = 4)
        val suggestions = ControlNode(
            id = 5, resourceId = "$chrome:id/omnibox_suggestions_dropdown",
            className = "android.widget.ListView", bounds = "0 220 1080 1400",
            enabled = true, sourceWindowId = 10, sourcePackage = chrome,
        )
        val rep2Start = ControlFrame(
            packageName = chrome, windowId = 10, foregroundWindowId = 10,
            fingerprint = "fp-rep2-start", nodes = listOf(rep1ResidualBar, suggestions),
            touchContext = "rot0-w10-w20", windowContext = snapWithIme.windowContext(ime),
            windowSnapshot = snapWithIme,
            configurationRevision = 1L, keyboard = ime,
            inputTargetId = rep1ResidualBar.id, inputTarget = rep1ResidualBar,
        )
        val rep2AfterBack1 = rep2Start.copy(
            fingerprint = "fp-rep2-back1",
            windowContext = snapNoIme.windowContext(null), windowSnapshot = snapNoIme,
            keyboard = null,
        )
        val menuNode = ControlNode(
            id = 30, resourceId = "$chrome:id/menu_button", description = "옵션 더보기",
            className = "android.widget.ImageButton", bounds = "960 100 1060 200",
            clickable = true, enabled = true, sourceWindowId = 10, sourcePackage = chrome,
        )
        val tabSwitcher9 = ControlNode(
            id = 31, resourceId = "$chrome:id/tab_switcher_button", description = "열려 있는 탭 9개",
            className = "android.widget.ImageButton", bounds = "840 100 940 200",
            clickable = true, enabled = true, sourceWindowId = 10, sourcePackage = chrome,
        )
        val searchBox = ControlNode(
            id = 32, resourceId = "$chrome:id/search_box_text", text = "", empty = true,
            className = "android.widget.EditText", bounds = "60 600 1020 720",
            clickable = true, enabled = true, editable = false, sourceWindowId = 10, sourcePackage = chrome,
        )
        val rep2AfterBack2 = ControlFrame(
            packageName = chrome, windowId = 10, foregroundWindowId = 10,
            fingerprint = "fp-rep2-ntp", nodes = listOf(menuNode, tabSwitcher9, searchBox),
            touchContext = "rot0-w10", windowContext = snapNoIme.windowContext(null),
            windowSnapshot = snapNoIme,
            configurationRevision = 1L, keyboard = null,
        )
        val newTabMenuNode = ControlNode(
            id = 40, resourceId = "$chrome:id/new_tab_menu_id",
            className = "android.widget.LinearLayout", bounds = "500 220 1000 320",
            clickable = true, enabled = true, sourceWindowId = 10, sourcePackage = chrome,
        )
        val newTabMenuLabel = ControlNode(
            id = 41, parent = 40, resourceId = "$chrome:id/menu_item_text", text = "새 탭",
            className = "android.widget.TextView", bounds = "520 230 980 310",
            enabled = true, sourceWindowId = 10, sourcePackage = chrome,
        )
        val menuPopupFrame = rep2AfterBack2.copy(
            fingerprint = "fp-menu", nodes = listOf(menuNode, tabSwitcher9, newTabMenuNode, newTabMenuLabel),
        )
        val tabSwitcher10 = tabSwitcher9.copy(description = "열려 있는 탭 10개")
        val newBlankTabFrame = rep2AfterBack2.copy(
            fingerprint = "fp-tab10", nodes = listOf(menuNode, tabSwitcher10, searchBox),
        )
        val blankFocusedBar = editor(text = "", sel = 0)
        val focusedTab10 = ControlFrame(
            packageName = chrome, windowId = 10, foregroundWindowId = 10,
            fingerprint = "fp-tab10-focused", nodes = listOf(blankFocusedBar) + hangulNodes(),
            touchContext = "rot0-w10-w20", windowContext = snapWithIme.windowContext(ime),
            windowSnapshot = snapWithIme,
            configurationRevision = 1L, keyboard = ime,
            inputTargetId = blankFocusedBar.id, inputTarget = blankFocusedBar,
        )

        // 1. Original blank predicate MUST still strictly reject non-empty "가 나 "!
        assertFalse(DebugOneStopQa.isGuardedBlankFocusedOmnibox(rep2Start))
        // 2. Narrow prior-benchmark predicate allows ONLY exact "가 나 " with caret 4/4 when passed!
        assertFalse(DebugOneStopQa.isGuardedPriorBenchmarkFocusedOmnibox(rep2Start, null))
        assertFalse(DebugOneStopQa.isGuardedPriorBenchmarkFocusedOmnibox(rep2Start, "hello"))
        assertTrue(DebugOneStopQa.isGuardedPriorBenchmarkFocusedOmnibox(rep2Start, "가 나 "))
        // Wrong caret or arbitrary user draft is strictly rejected!
        val wrongCaret = rep2Start.copy(
            nodes = listOf(editor(text = "가 나 ", sel = 2), suggestions),
            inputTarget = editor(text = "가 나 ", sel = 2),
        )
        assertFalse(DebugOneStopQa.isGuardedPriorBenchmarkFocusedOmnibox(wrongCaret, "가 나 "))
        val userDraft = rep2Start.copy(
            nodes = listOf(editor(text = "user draft", sel = 10), suggestions),
            inputTarget = editor(text = "user draft", sel = 10),
        )
        assertFalse(DebugOneStopQa.isGuardedPriorBenchmarkFocusedOmnibox(userDraft, "가 나 "))

        // 3. guardedBackObservationMatches requires exact live text + caret 4/4 for prior benchmark mode
        val obsJsonMatch = buildJsonObject {
            put("observation_id", "obs-back-1"); put("output_truncated", false)
            put("security_scan_complete", true); put("truncated", false); put("navigation_only", false)
            put("node_actions_allowed", true); put("package_name", chrome)
            put("window_id", 10); put("foreground_window_id", 10); put("configuration_revision", 1L)
            put("navigation_actions", buildJsonArray { add("back") })
            put("keyboard", buildJsonObject { put("window_id", 20); put("package_name", samsungIme) })
            put("nodes", buildJsonArray {
                add(buildJsonObject {
                    put("source_window_id", 10); put("resource_id", "$chrome:id/url_bar")
                    put("focused", true); put("editable", true); put("enabled", true)
                    put("text", "가 나 "); put("selection_start", 4); put("selection_end", 4)
                    put("bounds", rep1ResidualBar.bounds); put("class_name", rep1ResidualBar.className)
                })
            })
        }
        assertFalse(DebugOneStopQa.guardedBackObservationMatches(rep2Start, obsJsonMatch, null))
        assertTrue(DebugOneStopQa.guardedBackObservationMatches(rep2Start, obsJsonMatch, "가 나 "))

        // 4. Full Rep #2 preparation succeeds: Back #1 (hide IME) -> Back #2 (restore NTP) -> Menu -> New Tab (9->10) -> Focus blank url_bar!
        var uiState = "rep2_start"
        val ready = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = {
                when (uiState) {
                    "rep2_start" -> rep2Start
                    "after_back1" -> rep2AfterBack1
                    "after_back2" -> rep2AfterBack2
                    "menu_open" -> menuPopupFrame
                    "tab10_ntp" -> newBlankTabFrame
                    "tab10_focused" -> focusedTab10
                    else -> error("unexpected state $uiState")
                }
            },
            inspectWindow = { DebugOneStopQa.ChromeWindowFacts(true, true, true, true, true, true, true, false) },
            clickNode = { _, _, phase ->
                uiState = when (phase) {
                    "chrome_menu_dispatch" -> "menu_open"
                    "chrome_new_tab_dispatch" -> "tab10_ntp"
                    "chrome_editor_focus_dispatch" -> "tab10_focused"
                    else -> uiState
                }
                DebugOneStopQa.ChromeActionOutcome(true, true, false)
            },
            dispatchBack = { _, phase ->
                uiState = when (phase) {
                    "chrome_omnibox_back_1_dispatch" -> "after_back1"
                    "chrome_omnibox_back_2_dispatch" -> "after_back2"
                    else -> uiState
                }
                DebugOneStopQa.ChromeActionOutcome(true, true, false)
            },
            allowed = { true }, diagnosticOnly = false, onPhase = {}, pause = {},
            priorVerifiedBenchmarkText = "가 나 ",
        )
        assertNull("Rep #2 must succeed cleanly, got ${ready.failure}", ready.failure)
        assertEquals(2, ready.omniboxBackAttempts)
        assertEquals(9, ready.tabsBefore)
        assertEquals(10, ready.tabsAfter)
        assertTrue(ready.newTabVerified)
        assertEquals("", ready.frame?.inputTarget?.text)

        // 5. Rep #1 (priorVerifiedBenchmarkText == null) with same non-empty text MUST STILL REFUSE!
        val refusedRep1 = DebugOneStopQa.prepareChromeEditorWithSeam(
            capture = { rep2Start },
            inspectWindow = { DebugOneStopQa.ChromeWindowFacts(true, true, true, true, true, true, true, false) },
            clickNode = { _, _, _ -> error("must not click") },
            dispatchBack = { _, _ -> error("must not dispatch Back on Rep #1 with non-empty text") },
            allowed = { true }, diagnosticOnly = false, onPhase = {}, pause = {},
            priorVerifiedBenchmarkText = null,
        )
        assertEquals("unique_chrome_menu_unavailable", refusedRep1.failure)
        assertEquals(0, refusedRep1.omniboxBackAttempts)
    }

    @Test
    fun sessionReplacementBeforeFinalPostcondition_refusesPassAndPreservesReplacementSession() = runBlocking {
        // Real ControlSession: start run's session #1, complete 6-key dispatch, then end(#1) + start(#2)
        // before final postcondition capture. Even if a loose `allowed` predicate still sees an ACTIVE
        // session (#2), QA postcondition MUST reject PASS and finally cleanup MUST NOT end session #2!
        val session = ControlSession()
        val ownedToken = session.startAndSnapshot()!!
        var replacementToken: ControlSession.ActiveToken? = null
        var captureCount = 0
        var finalCaptureAttemptedAfterReplacement = false
        var invalidations = 0

        val looseAllowed = { session.explicit && session.state == ControlSession.State.ACTIVE }
        val strictSessionCurrent = { DebugOneStopQa.ownsActiveSession(session, ownedToken) }
        val f0 = hangulFrame("fp-0")
        val out = DebugSixKeyExecution.runWithSeam(
            captureFrame = {
                captureCount++
                if (captureCount == 1) {
                    f0
                } else {
                    finalCaptureAttemptedAfterReplacement = true
                    hangulFrame("fp-final-replaced", text = "가 나 ", sel = 4)
                }
            },
            observeSequence = { DebugSixKeyExecution.ObservePacket(true, obsData("obs-1", "vis-1"), certifiedFrame = f0) },
            certifyCandidate = { _, _, cand, _ ->
                PixelCertificationOutcome(
                    crop = TapBounds(10, 10, 80, 80),
                    method = if (cand.imageOnly) "space_glyph" else "native_structural_and_live_surface",
                )
            },
            dispatchSequence = { _, _ ->
                // Simulate external session replacement immediately after dispatch succeeds!
                session.end()
                replacementToken = session.startAndSnapshot()!!
                DebugSixKeyExecution.SequencePacket(true, buildJsonObject {
                    put("action_attempted", true); put("outcome_unknown", false)
                    put("app_result_verified", true); put("completed_keys", 6)
                })
            },
            sessionId = ownedToken.id,
            sessionEpoch = ownedToken.epoch,
            first = f0,
            allowed = looseAllowed,
            invalidateObservation = { invalidations++ },
            sessionCurrent = strictSessionCurrent,
            commitPass = { session.commitIfCurrentActive(ownedToken) { looseAllowed() } == true },
            checkpoint = { _, _ -> },
        )

        assertEquals("control_authority_revoked", out.failure)
        assertTrue("Post-dispatch session replacement must remain uncertain/fail-closed", out.progress.uncertain)
        assertFalse("Must refuse before running final capture under replaced session", finalCaptureAttemptedAfterReplacement)
        assertTrue("Must invalidate observations on session change", invalidations > 0)
        assertNotEquals(ownedToken, replacementToken)

        // Atomic compare-and-end MUST NOT end the replacement session!
        assertFalse(session.endIfCurrentActive(ownedToken))
        assertTrue(session.isCurrentActive(replacementToken))
        assertTrue(session.endIfCurrentActive(replacementToken))
    }

    @Test
    fun adversarialLifecycleMatrix_sameIdPauseResume_midCaptureRace_passCommitRace_andMutexCleanup() = runBlocking {
        val f0 = hangulFrame("fp-0")
        val fFinal = hangulFrame("fp-final", text = "가 나 ", sel = 4)

        // Case 1: Same-ID pause -> resume before final postcondition (ID unchanged, epoch incremented!)
        val s1 = ControlSession()
        val tok1 = s1.startAndSnapshot()!!
        var cap1 = 0
        val outPauseResume = DebugSixKeyExecution.runWithSeam(
            captureFrame = {
                cap1++
                if (cap1 == 1) f0 else {
                    // Race DURING final capture: pause -> beginResume -> finishResume (SAME ID, new epoch!)
                    s1.pause()
                    assertTrue(s1.beginResume())
                    s1.finishResume(s1.epoch, safe = true)
                    assertEquals(tok1.id, s1.id)
                    assertNotEquals(tok1.epoch, s1.epoch)
                    fFinal
                }
            },
            observeSequence = { DebugSixKeyExecution.ObservePacket(true, obsData("obs-1", "vis-1"), certifiedFrame = f0) },
            certifyCandidate = { _, _, cand, _ ->
                PixelCertificationOutcome(TapBounds(10, 10, 80, 80),
                    if (cand.imageOnly) "space_glyph" else "native_structural_and_live_surface")
            },
            dispatchSequence = { _, _ ->
                DebugSixKeyExecution.SequencePacket(true, buildJsonObject {
                    put("action_attempted", true); put("outcome_unknown", false)
                    put("app_result_verified", true); put("completed_keys", 6)
                })
            },
            sessionId = tok1.id, sessionEpoch = tok1.epoch, first = f0,
            allowed = { s1.explicit && s1.state == ControlSession.State.ACTIVE },
            sessionCurrent = { s1.isCurrentActive(tok1) },
            commitPass = { s1.commitIfCurrentActive(tok1) { true } == true },
            checkpoint = { _, _ -> },
        )
        assertEquals("control_authority_revoked", outPauseResume.failure)
        assertTrue(outPauseResume.progress.uncertain)
        assertFalse("Old token must NOT end resumed same-ID session", s1.endIfCurrentActive(tok1))
        assertEquals(ControlSession.State.ACTIVE, s1.state)
        s1.end()

        // Case 2: Transition BETWEEN final read validation and PASS commit linearization point!
        val s2 = ControlSession()
        val tok2 = s2.startAndSnapshot()!!
        var cap2 = 0
        var commitChecked = false
        val outCommitRace = DebugSixKeyExecution.runWithSeam(
            captureFrame = { cap2++; if (cap2 == 1) f0 else fFinal },
            observeSequence = { DebugSixKeyExecution.ObservePacket(true, obsData("obs-1", "vis-1"), certifiedFrame = f0) },
            certifyCandidate = { _, _, cand, _ ->
                PixelCertificationOutcome(TapBounds(10, 10, 80, 80),
                    if (cand.imageOnly) "space_glyph" else "native_structural_and_live_surface")
            },
            dispatchSequence = { _, _ ->
                DebugSixKeyExecution.SequencePacket(true, buildJsonObject {
                    put("action_attempted", true); put("outcome_unknown", false)
                    put("app_result_verified", true); put("completed_keys", 6)
                })
            },
            sessionId = tok2.id, sessionEpoch = tok2.epoch, first = f0,
            allowed = { true },
            sessionCurrent = { s2.isCurrentActive(tok2) },
            commitPass = {
                // Simulate concurrent user manualTakeover racing RIGHT at the PASS commit boundary!
                s2.manualTakeover()
                commitChecked = true
                s2.commitIfCurrentActive(tok2) { true } == true
            },
            checkpoint = { _, _ -> },
        )
        assertTrue(commitChecked)
        assertEquals("control_authority_revoked", outCommitRace.failure)
        assertTrue(outCommitRace.progress.uncertain)
        // User manual takeover PAUSED state MUST be preserved by atomic cleanup!
        assertFalse(s2.endIfCurrentActive(tok2))
        assertEquals(ControlSession.State.PAUSED, s2.state)

        // Case 3: Cleanup cancellation / exception at EVERY step STILL unlocks Mutex & surfaces redacted errors!
        val mutex = kotlinx.coroutines.sync.Mutex()
        val reportedSteps = mutableListOf<String>()
        for (failAt in listOf("invalidate", "stopActivity", "endSession")) {
            assertTrue("Mutex must be acquirable before $failAt", mutex.tryLock())
            DebugOneStopQa.cleanupSessionLease(
                invalidate = { if (failAt == "invalidate") throw IllegalStateException("boom-inv") },
                stopActivity = { if (failAt == "stopActivity") throw IllegalStateException("boom-act") },
                endOwnedSession = { if (failAt == "endSession") throw IllegalStateException("boom-end") },
                unlockMutex = { mutex.unlock() },
                onCleanupError = { step, _ -> reportedSteps += step },
            )
            assertTrue("Mutex MUST be reacquirable after $failAt failure", mutex.tryLock())
            mutex.unlock()
        }
        assertEquals(listOf("invalidate", "stop_activity", "end_session"), reportedSteps)

        // Case 4: Genuine 2-dispatcher coroutine handoff prompt cancellation during startSessionAndRecord!
        val mainThreadExec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val mainDispatcher = mainThreadExec.asCoroutineDispatcher()
        try {
            val sCancel = ControlSession()
            val stalePreStartRef = sCancel.id
            val ownedRef = java.util.concurrent.atomic.AtomicReference<ControlSession.ActiveToken?>(null)
            var correlatedRef = stalePreStartRef
            val cancelMutex = kotlinx.coroutines.sync.Mutex()
            assertTrue(cancelMutex.tryLock())
            lateinit var runnerJob: kotlinx.coroutines.Job
            var caughtCancel = false
            runnerJob = launch(kotlinx.coroutines.Dispatchers.Default) {
                try {
                    DebugOneStopQa.startSessionAndRecord(
                        mainDispatcher = mainDispatcher,
                        startAndSnapshot = { sCancel.startAndSnapshot() },
                        recordOwned = { token ->
                            ownedRef.set(token)
                            correlatedRef = token.id
                            // Trigger prompt cancellation WHILE still inside mainDispatcher before handoff back!
                            runnerJob.cancel()
                        },
                    )
                } catch (_: kotlinx.coroutines.CancellationException) {
                    caughtCancel = true
                } finally {
                    val tokenToEnd = ownedRef.get()
                    DebugOneStopQa.cleanupSessionLease(
                        invalidate = {},
                        stopActivity = {},
                        endOwnedSession = {
                            if (tokenToEnd != null) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + mainDispatcher) {
                                    sCancel.endIfCurrentActive(tokenToEnd)
                                }
                            }
                        },
                        unlockMutex = { cancelMutex.unlock() },
                    )
                }
            }
            runnerJob.join()
            assertTrue("Prompt cancellation must propagate across dispatcher handoff", caughtCancel)
            val recorded = ownedRef.get()
            assertNotNull("Owned token MUST be recorded inside Main block before cancellation unwinds", recorded)
            assertEquals("Correlation reference MUST match newly started session, not stale pre-start ID", recorded!!.id, correlatedRef)
            assertNotEquals(stalePreStartRef, correlatedRef)
            assertEquals("Orphaned started session MUST be ended by finally cleanup", ControlSession.State.ENDED, sCancel.state)
            assertTrue("Mutex MUST be unlocked after dispatcher-handoff cancellation", cancelMutex.tryLock())
            cancelMutex.unlock()
        } finally {
            mainThreadExec.shutdownNow()
        }
    }

    @Test
    fun finalAndInitialCompleteness_rejectsTruncatedAndNavigationOnly_andNullIdReplacements_preservesStableAnonymous() = runBlocking {
        val f0 = hangulFrame("fp-0")
        val fCompleteFinal = hangulFrame("fp-fin", text = "가 나 ", sel = 4)

        // 1. Normal complete final read -> PASS!
        val framesOk = ArrayDeque(listOf(f0, fCompleteFinal))
        val passOut = DebugSixKeyExecution.runWithSeam(
            captureFrame = { framesOk.removeFirst() },
            observeSequence = { DebugSixKeyExecution.ObservePacket(true, obsData("obs-1", "vis-1"), certifiedFrame = f0) },
            certifyCandidate = { _, _, cand, _ ->
                PixelCertificationOutcome(TapBounds(10, 10, 80, 80),
                    if (cand.imageOnly) "space_glyph" else "native_structural_and_live_surface")
            },
            dispatchSequence = { _, _ ->
                DebugSixKeyExecution.SequencePacket(true, buildJsonObject {
                    put("action_attempted", true); put("outcome_unknown", false)
                    put("app_result_verified", true); put("completed_keys", 6)
                })
            },
            sessionId = "s-ok", sessionEpoch = 1L, first = f0, allowed = { true }, checkpoint = { _, _ -> },
        )
        assertNull("Normal complete final read must PASS, got ${passOut.failure}", passOut.failure)
        assertFalse(passOut.progress.uncertain)

        // 2. Truncated outer six_key_final (reason == null, truncated == true) with matching "가 나 " (4/4) -> REJECTED!
        val framesTrunc = ArrayDeque(listOf(f0, fCompleteFinal.copy(truncated = true)))
        val truncOut = DebugSixKeyExecution.runWithSeam(
            captureFrame = { framesTrunc.removeFirst() },
            observeSequence = { DebugSixKeyExecution.ObservePacket(true, obsData("obs-1", "vis-1"), certifiedFrame = f0) },
            certifyCandidate = { _, _, cand, _ ->
                PixelCertificationOutcome(TapBounds(10, 10, 80, 80),
                    if (cand.imageOnly) "space_glyph" else "native_structural_and_live_surface")
            },
            dispatchSequence = { _, _ ->
                DebugSixKeyExecution.SequencePacket(true, buildJsonObject {
                    put("action_attempted", true); put("outcome_unknown", false)
                    put("app_result_verified", true); put("completed_keys", 6)
                })
            },
            sessionId = "s-trunc", sessionEpoch = 1L, first = f0, allowed = { true }, checkpoint = { _, _ -> },
        )
        assertEquals("six_key_final_read_unconfirmed", truncOut.failure)
        assertTrue(truncOut.progress.uncertain)

        // 3. navigationOnly outer six_key_final -> REJECTED!
        val framesNav = ArrayDeque(listOf(f0, fCompleteFinal.copy(navigationOnly = true)))
        val navOut = DebugSixKeyExecution.runWithSeam(
            captureFrame = { framesNav.removeFirst() },
            observeSequence = { DebugSixKeyExecution.ObservePacket(true, obsData("obs-1", "vis-1"), certifiedFrame = f0) },
            certifyCandidate = { _, _, cand, _ ->
                PixelCertificationOutcome(TapBounds(10, 10, 80, 80),
                    if (cand.imageOnly) "space_glyph" else "native_structural_and_live_surface")
            },
            dispatchSequence = { _, _ ->
                DebugSixKeyExecution.SequencePacket(true, buildJsonObject {
                    put("action_attempted", true); put("outcome_unknown", false)
                    put("app_result_verified", true); put("completed_keys", 6)
                })
            },
            sessionId = "s-nav", sessionEpoch = 1L, first = f0, allowed = { true }, checkpoint = { _, _ -> },
        )
        assertEquals("six_key_final_read_unconfirmed", navOut.failure)
        assertTrue(navOut.progress.uncertain)

        // 4. Truncated initial `before` frame -> REJECTED before any dispatch!
        val initTruncOut = DebugSixKeyExecution.runWithSeam(
            captureFrame = { f0.copy(truncated = true) },
            observeSequence = { error("must not observe after truncated initial before") },
            certifyCandidate = { _, _, _, _ -> error("not reached") },
            dispatchSequence = { _, _ -> error("not reached") },
            sessionId = "s-init-trunc", sessionEpoch = 1L, first = f0, allowed = { true }, checkpoint = { _, _ -> },
        )
        assertEquals("editor_checkpoint_changed", initTruncOut.failure)

        // 5. Null nativeEditorId: simultaneous duplicate & structural one-for-one replacement REJECTED;
        //    genuine stable anonymous WebView editor ACCEPTED!
        val nullIdBar = ControlNode(
            id = 1, text = "", resourceId = "$chrome:id/url_bar",
            clickable = true, editable = true, enabled = true, empty = true,
            bounds = "0 100 1080 220", parent = 50, className = "android.widget.EditText",
            focused = true, focusable = true, selectionStart = 0, selectionEnd = 0,
            sourceWindowId = 10, sourcePackage = chrome, nativeEditorId = null,
        )
        val nullBefore = f0.copy(nodes = listOf(nullIdBar) + hangulNodes(), inputTargetId = 1, inputTarget = nullIdBar)
        val nullAfterSame = nullIdBar.copy(text = "가 나 ", empty = false, selectionStart = 4, selectionEnd = 4)
        val dupPeer = nullIdBar.copy(id = 2, focused = false, bounds = "0 300 1080 420")
        assertFalse("Simultaneous duplicate resourceId must fail",
            DebugOneStopQa.sameEditor(nullBefore, nullBefore.copy(nodes = listOf(nullAfterSame, dupPeer) + hangulNodes(), inputTarget = nullAfterSame)))
        assertFalse("One-for-one replacement with changed parent must fail",
            DebugOneStopQa.sameEditor(nullBefore, nullBefore.copy(nodes = listOf(nullAfterSame.copy(parent = 99)) + hangulNodes(), inputTarget = nullAfterSame.copy(parent = 99))))
        assertFalse("One-for-one replacement with changed bounds must fail",
            DebugOneStopQa.sameEditor(nullBefore, nullBefore.copy(nodes = listOf(nullAfterSame.copy(bounds = "0 400 1080 520")) + hangulNodes(), inputTarget = nullAfterSame.copy(bounds = "0 400 1080 520"))))
        assertFalse("Mixed null vs non-null nativeEditorId must fail",
            DebugOneStopQa.sameEditor(nullBefore, nullBefore.copy(nodes = listOf(nullAfterSame.copy(nativeEditorId = "new-id")) + hangulNodes(), inputTarget = nullAfterSame.copy(nativeEditorId = "new-id"))))

        // Genuine stable anonymous editor (blank resourceId, null nativeEditorId, same WebView container/parent/bounds)
        val anonEd = ControlNode(
            id = 7, text = "", resourceId = "",
            clickable = true, editable = true, enabled = true, empty = true,
            bounds = "40 500 1000 620", parent = 70, webEditorContainer = 70,
            className = "android.widget.EditText", focused = true, focusable = true,
            selectionStart = 0, selectionEnd = 0, sourceWindowId = 10, sourcePackage = chrome,
            nativeEditorId = null,
        )
        val anonAfter = anonEd.copy(text = "가 나 ", empty = false, selectionStart = 4, selectionEnd = 4)
        val anonBeforeFrame = f0.copy(nodes = listOf(anonEd) + hangulNodes(), inputTargetId = 7, inputTarget = anonEd)
        val anonAfterFrame = f0.copy(nodes = listOf(anonAfter) + hangulNodes(), inputTargetId = 7, inputTarget = anonAfter)
        assertTrue("Stable anonymous WebView editor must remain accepted",
            DebugOneStopQa.sameEditor(anonBeforeFrame, anonAfterFrame))
        assertTrue("KeyboardEditorContinuity must also accept stable anonymous WebView editor",
            KeyboardEditorContinuity.sameFocusedEditor(anonBeforeFrame, anonAfterFrame, allowAnonymousResize = true))
    }
}
