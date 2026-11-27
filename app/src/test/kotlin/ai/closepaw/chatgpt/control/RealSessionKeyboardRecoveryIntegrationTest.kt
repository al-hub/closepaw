package ai.closepaw.chatgpt.control

import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import ai.closepaw.security.ImageRevocationAuthority
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Real ControlSession + real GenericControl + real VisualImageCache + real
 * AutonomousSequenceLedger integration tests for #147 P0/P1 structural fixes.
 */
class RealSessionKeyboardRecoveryIntegrationTest {
    private val chrome = "com.android.chrome"
    private val samsungIme = "com.samsung.android.honeyboard"
    private val imeId = "$samsungIme/.service.HoneyBoardService"
    private val ime = ControlKeyboard(20, samsungIme, imeId, readAllowed = true, interactAllowed = true)

    @Before
    fun resetAuthorityAndCache() {
        ImageRevocationAuthority.revokeConsent()
        ImageRevocationAuthority.grantConsent()
        VisualImageCache.clear()
    }

    private fun editor(
        text: String = "",
        selStart: Int = text.length,
        selEnd: Int = text.length,
        focusable: Boolean = true,
        cursorGen: Long? = 100L,
        nativeId: String = "chrome-url-1",
    ) = ControlNode(
        id = 1, text = text, resourceId = "$chrome:id/url_bar",
        clickable = true, editable = true, enabled = true, empty = text.isEmpty(),
        bounds = "0 100 1080 220", className = "android.widget.EditText",
        focused = true, focusable = focusable, selectionStart = selStart, selectionEnd = selEnd,
        sourceWindowId = 10, sourcePackage = chrome, nativeEditorId = nativeId,
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
        val leftAnchor = ControlNode(
            id = 149, text = ",", description = ",",
            bounds = "190 2000 290 2120", parent = 502,
            className = "android.widget.Button", enabled = true, clickable = true,
            sourceWindowId = 20, sourcePackage = samsungIme,
            keyboardKey = ",", visualKeyCandidate = true, textEntryKey = true,
            visualKeyChildCount = 0,
        )
        val space = ControlNode(
            id = 150, bounds = "300 2000 780 2120", parent = 502,
            className = "android.view.View", enabled = true, clickable = true,
            sourceWindowId = 20, sourcePackage = samsungIme,
            keyboardImageCandidate = true, visualKeyChildCount = 0,
        )
        val rightAnchor = ControlNode(
            id = 151, text = ".", description = ".",
            bounds = "790 2000 890 2120", parent = 502,
            className = "android.widget.Button", enabled = true, clickable = true,
            sourceWindowId = 20, sourcePackage = samsungIme,
            keyboardKey = ".", visualKeyCandidate = true, textEntryKey = true,
            visualKeyChildCount = 0,
        )
        return keys1 + keys2 + listOf(leftAnchor, space, rightAnchor)
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

    private fun frame(
        nodes: List<ControlNode>,
        ed: ControlNode = editor(),
        fp: String = "fp-1",
        touchContext: String = "rot0-w10-w20",
        windowContext: String = "wctx-1",
        kb: ControlKeyboard? = ime,
        cursorFailure: CursorProofFailure? = null,
    ) = ControlFrame(
        packageName = chrome, windowId = 10, foregroundWindowId = 10,
        fingerprint = fp, nodes = listOf(ed) + nodes,
        touchContext = touchContext, windowContext = windowContext,
        configurationRevision = 1L, keyboard = kb,
        inputTargetId = ed.id, inputTarget = ed,
        cursorProofFailure = cursorFailure,
    )

    private fun makeProof(
        id: String,
        displayGen: Long,
        displayValid: () -> Boolean,
        session: ControlSession,
        consentEpoch: Long = ImageRevocationAuthority.currentEpoch,
        at: Long = System.currentTimeMillis(),
    ): VisualTargetProof {
        val gate = VisualSourceGate(
            consentValid = { ImageRevocationAuthority.isConsentStillValid(consentEpoch) },
            displayValid = displayValid,
            sourceValid = { true },
            sessionValid = { session.explicit && session.state == ControlSession.State.ACTIVE },
        )
        return VisualTargetProof(
            image = ScreenImage(432, 960, "image/jpeg", ByteArray(64) { 7 }, ScreenImageSource.MEDIA_PROJECTION),
            pixelHash = "px-$id",
            window = TapBounds(0, 0, 1080, 2400),
            context = "rot0-w10-w20",
            at = at,
            id = id,
            scope = "keyboard",
            sourceWindowId = 20,
            sourceDisplayId = 0,
            rotation = 0,
            applicationBounds = "0 0 1080 1500",
            imeBounds = "0 1500 1080 2400",
            sourceImeWindowId = 20,
            displayGeneration = displayGen,
            disclosedRegions = listOf(TapBounds(0, 1500, 1080, 2400)),
            editorPrivacyGeneration = 7L,
            sourceDisclosureAllowed = gate::allowed,
            sourceDisclosureGate = gate,
        )
    }

    @Test
    fun bidirectionalCursorProofMismatchClassifier_allowsOnlyStrictOneSidedScreenChangeRace() {
        val provenEd = editor(text = "", selStart = 0, selEnd = 0, cursorGen = 55L)
        val unprovedEd = editor(text = "", selStart = -1, selEnd = -1, cursorGen = null)

        val provenFrame = frame(hangulNodes(), ed = provenEd, cursorFailure = null)
        val raceFrame = frame(hangulNodes(), ed = unprovedEd, cursorFailure = CursorProofFailure.SCREEN_CHANGE_RACE)

        // AFTER race: before has valid gen, after hit SCREEN_CHANGE_RACE -> recoverable
        val afterRace = KeyboardCursorProof.classifyContextMismatch(provenFrame, raceFrame)
        assertEquals(KeyboardContextMismatchKind.CURSOR_PROOF_CHANGED, afterRace.kind)
        assertEquals(CursorRaceLocation.AFTER, afterRace.location)
        assertNull(afterRace.beforeFailure)
        assertEquals(CursorProofFailure.SCREEN_CHANGE_RACE, afterRace.afterFailure)
        assertTrue(afterRace.recoverableReadOnlyCursorRace)

        // BEFORE race: before hit SCREEN_CHANGE_RACE, after has valid gen -> recoverable
        val beforeRace = KeyboardCursorProof.classifyContextMismatch(raceFrame, provenFrame)
        assertEquals(KeyboardContextMismatchKind.CURSOR_PROOF_CHANGED, beforeRace.kind)
        assertEquals(CursorRaceLocation.BEFORE, beforeRace.location)
        assertEquals(CursorProofFailure.SCREEN_CHANGE_RACE, beforeRace.beforeFailure)
        assertNull(beforeRace.afterFailure)
        assertTrue(beforeRace.recoverableReadOnlyCursorRace)

        // Non-race cursor failure causes MUST fail closed
        for (cause in listOf(
            CursorProofFailure.IPC_NULL,
            CursorProofFailure.NONEMPTY_OR_SELECTION,
            CursorProofFailure.EDITOR_MISMATCH,
            CursorProofFailure.UNAVAILABLE,
            null,
        )) {
            val bad = frame(hangulNodes(), ed = unprovedEd, cursorFailure = cause)
            assertFalse("after cause=$cause must fail closed",
                KeyboardCursorProof.classifyContextMismatch(provenFrame, bad).recoverableReadOnlyCursorRace)
            assertFalse("before cause=$cause must fail closed",
                KeyboardCursorProof.classifyContextMismatch(bad, provenFrame).recoverableReadOnlyCursorRace)
        }

        // Different non-null generations -> editor changed -> fail closed
        val otherGen = frame(hangulNodes(), ed = provenEd.copy(keyboardCursorGeneration = 56L))
        val diffGen = KeyboardCursorProof.classifyContextMismatch(provenFrame, otherGen)
        assertEquals(CursorRaceLocation.BOTH, diffGen.location)
        assertFalse(diffGen.recoverableReadOnlyCursorRace)

        // Same inputTargetId alone is NEVER sufficient if focusable, editor identity, touchContext, windowContext, or value changed
        assertFalse(KeyboardCursorProof.classifyContextMismatch(
            provenFrame, raceFrame.copy(inputTarget = unprovedEd.copy(focusable = false)),
        ).recoverableReadOnlyCursorRace)
        assertFalse(KeyboardCursorProof.classifyContextMismatch(
            provenFrame, raceFrame.copy(inputTarget = unprovedEd.copy(nativeEditorId = "other-editor")),
        ).recoverableReadOnlyCursorRace)
        assertFalse(KeyboardCursorProof.classifyContextMismatch(
            provenFrame, raceFrame.copy(touchContext = "rot1-landscape"),
        ).recoverableReadOnlyCursorRace)
        assertFalse(KeyboardCursorProof.classifyContextMismatch(
            provenFrame, raceFrame.copy(windowContext = "wctx-overlay"),
        ).recoverableReadOnlyCursorRace)
        assertFalse(KeyboardCursorProof.classifyContextMismatch(
            provenFrame, raceFrame.copy(inputTarget = unprovedEd.copy(text = "x", empty = false)),
        ).recoverableReadOnlyCursorRace)
        assertFalse(KeyboardCursorProof.classifyContextMismatch(
            provenFrame, raceFrame.copy(keyboard = ime.copy(interactAllowed = false)),
        ).recoverableReadOnlyCursorRace)
        assertEquals(
            KeyboardContextMismatchKind.KEYBOARD_CHANGED,
            KeyboardCursorProof.classifyContextMismatch(provenFrame, raceFrame.copy(keyboard = null)).kind,
        )
        assertEquals(
            KeyboardContextMismatchKind.INPUT_TARGET_CHANGED,
            KeyboardCursorProof.classifyContextMismatch(provenFrame, raceFrame.copy(inputTargetId = 999)).kind,
        )
    }

    @Test
    fun visualImageCache_preservesDisplayWindowChangedWithoutDisclosingPixels_andConsentTakesPrecedence() {
        val session = ControlSession().apply { assertTrue(start()) }
        var displayGen = 10L
        val proof = makeProof("vis-toctou-1", displayGen = 10L, displayValid = { displayGen == 10L }, session = session)
        VisualImageCache.put(proof) { true }

        // Live hit returns proof
        val live = VisualImageCache.proofForOrFailure(proof.id, proof, VisualSourceStage.ACTION_VALIDATION)
        assertSame(proof, live.proof)
        assertNull(live.failure)

        // Display window changes -> prune() evicts entry, pixels NEVER disclosed, structured DISPLAY_WINDOW_CHANGED preserved
        displayGen = 11L
        val afterChange = VisualImageCache.proofForOrFailure(proof.id, proof, VisualSourceStage.ACTION_VALIDATION)
        assertNull("Evicted pixels must NEVER be disclosed", afterChange.proof)
        assertEquals(VisualSourceStage.ACTION_VALIDATION, afterChange.failure?.stage)
        assertEquals(VisualSourceCondition.DISPLAY_WINDOW_CHANGED, afterChange.failure?.condition)

        // Subsequent lookup (entry already pruned from map) still preserves DISPLAY_WINDOW_CHANGED via fallbackProof without exposing pixels
        val afterEvicted = VisualImageCache.proofForOrFailure(proof.id, proof, VisualSourceStage.ACTION_VALIDATION)
        assertNull(afterEvicted.proof)
        assertEquals(VisualSourceCondition.DISPLAY_WINDOW_CHANGED, afterEvicted.failure?.condition)

        // When consent is ALSO revoked, IMAGE_CONSENT_CHANGED MUST win over DISPLAY_WINDOW_CHANGED
        ImageRevocationAuthority.revokeConsent()
        val consentRevoked = VisualImageCache.proofForOrFailure(proof.id, proof, VisualSourceStage.ACTION_VALIDATION)
        assertNull(consentRevoked.proof)
        assertEquals(VisualSourceCondition.IMAGE_CONSENT_CHANGED, consentRevoked.failure?.condition)
        session.end()
    }

    @Test
    fun realControlSessionAndGenericControl_recoversAcrossDisplayChangeAndCursorRace_andBlocksPreGestureAndUnknown() = runBlocking {
        val session = ControlSession()
        assertTrue(session.start())
        assertEquals(ControlSession.State.ACTIVE, session.state)

        var displayGen = 1L
        var proofSeq = 0
        var layoutHangul = false
        var editorText = ""
        val ledger = AutonomousSequenceLedger()
        val phases = mutableListOf<ControlSession.VisualPhase>()
        val dispatchedNonces = mutableListOf<String>()

        var nextVisualFail: String? = null
        var nextCursorMismatch: KeyboardContextMismatch? = null
        var injectActionValidationChangeBeforeKey = -1
        var injectNativeValidationChangeBeforeKey = -1
        var injectPreGestureChangeBeforeKey = -1
        var injectUnknownAtKey = -1

        val port = object : ControlPort {
            override val validatesAtNativeBoundary: Boolean = true
            override val allowsAutonomousDebugKeyboardProof: Boolean = true
            private var lastFail = "visual_observation_unavailable"
            override fun session(): ControlSession = session
            override fun visualFailure(): String = lastFail
            override fun visualKeyboardContextMismatch(): KeyboardContextMismatch? = nextCursorMismatch
            override fun summarizeDisplayWindowChangesFor(proof: VisualTargetProof?): JsonObject = buildJsonObject {
                put("elapsed_ms", 120)
                put("unrecorded_count", 0)
                put("events", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "windows_changed")
                        put("reported_owner", "unknown")
                        put("event_window_match", "other")
                        put("event_display_class", "default")
                        put("window_change_mask", 8)
                        put("unknown_change_bits", false)
                    })
                })
            }
            override fun beginAutonomousKeyboardSequence(sequenceNonce: String, sessionId: String, epoch: Long, totalSteps: Int): Boolean =
                ledger.begin(sequenceNonce, sessionId, epoch, totalSteps)
            override fun advanceAutonomousKeyboardStep(sequenceNonce: String, completedStepIndex: Int) {
                ledger.advance(sequenceNonce, completedStepIndex)
            }
            override fun endAutonomousKeyboardSequence(sequenceNonce: String) {
                ledger.end(sequenceNonce)
            }
            override suspend fun presentation(phase: ControlSession.VisualPhase, expectedEpoch: Long?) {
                phases += phase
                session.present(phase, expectedEpoch)
            }
            override suspend fun capture(): ControlFrame {
                val ed = editor(text = editorText, selStart = editorText.length, selEnd = editorText.length)
                val nodes = if (layoutHangul) hangulNodes() else latinNodes()
                val fp = "fp-${if (layoutHangul) "ko" else "en"}-$editorText"
                return frame(nodes, ed = ed, fp = fp)
            }
            override suspend fun visualKeyboard(frame: ControlFrame): VisualTargetProof? {
                val fail = nextVisualFail
                if (fail != null) {
                    nextVisualFail = null
                    lastFail = fail
                    return null
                }
                val gen = displayGen
                val proof = makeProof("vis-${++proofSeq}", displayGen = gen, displayValid = { displayGen == gen }, session = session)
                return proof
            }
            override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
                val ctx = currentCoroutineContext()[AutonomousKeyboardDispatchContext]
                    ?: return ControlEffect(false, false, "visual_proof_invalid_or_expired")
                val proof = frame.visual ?: return ControlEffect(false, false, "visual_observation_required")
                if (injectNativeValidationChangeBeforeKey == ctx.stepIndex) {
                    injectNativeValidationChangeBeforeKey = -1
                    displayGen++
                }
                proof.sourceFailure(VisualSourceStage.NATIVE_ACTION_VALIDATION)?.let { failure ->
                    val enriched = if (failure.condition == VisualSourceCondition.DISPLAY_WINDOW_CHANGED) {
                        failure.copy(windowEvents = summarizeDisplayWindowChangesFor(proof))
                    } else failure
                    return ControlEffect(false, false, VISUAL_SOURCE_REFUSED, enriched)
                }
                if (injectPreGestureChangeBeforeKey == ctx.stepIndex) {
                    injectPreGestureChangeBeforeKey = -1
                    displayGen++
                    val failure = proof.sourceFailure(VisualSourceStage.PRE_GESTURE)!!
                    return ControlEffect(false, false, VISUAL_SOURCE_REFUSED, failure)
                }
                if (!ledger.verifyAndConsumeDispatch(ctx)) {
                    return ControlEffect(false, false, "visual_proof_invalid_or_expired")
                }
                dispatchedNonces += ctx.nonce
                if (ctx.kind == AutonomousDispatchKind.KOREAN_LAYOUT_SWITCH) {
                    layoutHangul = true
                    return ControlEffect(true, true, "visual_key_acknowledged")
                }
                if (injectUnknownAtKey == ctx.stepIndex) {
                    injectUnknownAtKey = -1
                    return ControlEffect(true, false, "gesture_cancelled")
                }
                editorText = if (ctx.label == "Space") editorText + " " else HangulKeyComposer.append(editorText, ctx.label)
                return ControlEffect(true, true, "visual_key_acknowledged")
            }
            override suspend fun apps(filter: String): JsonArray = buildJsonArray {}
            override fun invalidate() { ledger.clearActive() }
        }

        val control = GenericControl(port, allowed = { session.state == ControlSession.State.ACTIVE })

        // 1. Observe failure with visual_post_keyboard_context_changed (one-side SCREEN_CHANGE_RACE) MUST NOT end ControlSession!
        val provenFrame = frame(latinNodes(), ed = editor(text = "", selStart = 0, selEnd = 0, cursorGen = 100L))
        val raceFrame = frame(latinNodes(), ed = editor(text = "", selStart = -1, selEnd = -1, cursorGen = null),
            cursorFailure = CursorProofFailure.SCREEN_CHANGE_RACE)
        val mismatch = KeyboardCursorProof.classifyContextMismatch(provenFrame, raceFrame)
        nextCursorMismatch = mismatch
        assertTrue(mismatch.recoverableReadOnlyCursorRace)
        nextVisualFail = "visual_post_keyboard_context_changed"

        val failedObs = control.observe(includeImage = true, imageScope = "keyboard", keyboardKeyExact = "한/영")
        assertFalse(failedObs.success)
        assertEquals("visual_post_keyboard_context_changed", failedObs.data["reason_code"]?.jsonPrimitive?.content)
        // Session stayed ACTIVE even after VisualPhase.FAILED!
        assertEquals(ControlSession.State.ACTIVE, session.state)
        assertTrue(phases.contains(ControlSession.VisualPhase.FAILED))
        nextCursorMismatch = null

        // 2. certifiedKeyboardObservationDetailed preserves ACTION_VALIDATION + DISPLAY_WINDOW_CHANGED + window_events
        val switchObs1 = control.observe(includeImage = true, imageScope = "keyboard", keyboardKeyExact = "한/영")
        assertTrue(switchObs1.success)
        val obsId1 = switchObs1.data["observation_id"]!!.jsonPrimitive.content
        val visId1 = switchObs1.data["visual"]!!.jsonObject["visual_id"]!!.jsonPrimitive.content
        displayGen++ // invalidate display right after observe!
        val detailedLookup = control.certifiedKeyboardObservationDetailed(obsId1, visId1, session.epoch)
        assertNull(detailedLookup.certified)
        assertEquals(VisualSourceStage.ACTION_VALIDATION, detailedLookup.visualSourceFailure?.stage)
        assertEquals(VisualSourceCondition.DISPLAY_WINDOW_CHANGED, detailedLookup.visualSourceFailure?.condition)
        assertNotNull(detailedLookup.captureWindowChanges)

        // 3. Fresh observe + Korean layout switch succeeds via real visualKoreanLayoutSwitchAutonomous
        val switchObs2 = control.observe(includeImage = true, imageScope = "keyboard", keyboardKeyExact = "한/영")
        assertTrue(switchObs2.success)
        val swObsId = switchObs2.data["observation_id"]!!.jsonPrimitive.content
        val swVisId = switchObs2.data["visual"]!!.jsonObject["visual_id"]!!.jsonPrimitive.content
        val swCert = control.certifiedKeyboardObservationDetailed(swObsId, swVisId, session.epoch).certified!!
        val swNode = swCert.frame.nodes.single { it.keyboardKey == "한/영" }
        val swCrop = NativeKeyboardStructuralCertification.verifyKoreanLayoutSwitch(swCert.frame, swCert.proof, swNode).crop!!
        val swProof = AutonomousKeyboardSequenceProof(
            nonce = "sw-nonce-1", sessionId = session.id, sessionEpoch = session.epoch,
            observationId = swObsId, visualId = swVisId,
            steps = listOf(AutonomousCertifiedKeyStep("한/영", swNode.id, false, swCrop, "native_layout_switch_and_live_surface")),
        )
        val swRes = control.visualKoreanLayoutSwitchAutonomous(buildJsonObject {
            put("session_id", session.id); put("observation_id", swObsId); put("visual_id", swVisId)
            put("visual_target_confirmed", false); put("node_id", swNode.id)
        }, swProof)
        assertTrue("Expected Korean layout switch PASS: ${swRes.data}", swRes.success)
        assertTrue(layoutHangul)

        // 4. Full 6-key sequence ("가 나 ") with shared budget + 1 NATIVE_ACTION_VALIDATION DISPLAY_WINDOW_CHANGED
        //    on key #3 (index 2) + 1 recoverable cursor race during follow-up observe -> recovers without replaying keys 1..2!
        val seqLabels = listOf("ㄱ", "ㅏ", "ㄴ", "Space")
        val seqObs = control.observe(includeImage = true, imageScope = "keyboard", keyboardSequenceLabels = seqLabels)
        assertTrue(seqObs.success)
        val seqObsId = seqObs.data["observation_id"]!!.jsonPrimitive.content
        val seqVisId = seqObs.data["visual"]!!.jsonObject["visual_id"]!!.jsonPrimitive.content
        val seqCert = control.certifiedKeyboardObservationDetailed(seqObsId, seqVisId, session.epoch).certified!!

        val keyByLabel = mapOf(
            "ㄱ" to seqCert.frame.nodes.single { it.keyboardKey == "ㄱ" },
            "ㅏ" to seqCert.frame.nodes.single { it.keyboardKey == "ㅏ" },
            "ㄴ" to seqCert.frame.nodes.single { it.keyboardKey == "ㄴ" },
            "Space" to seqCert.frame.nodes.single { it.keyboardImageCandidate },
        )
        val cropByLabel = keyByLabel.mapValues { (label, node) ->
            if (node.keyboardImageCandidate) {
                KeyboardImageFallbackPolicy.crop(seqCert.proof, KeyboardImageFallbackPolicy.bounds(node.bounds)!!)!!
            } else {
                NativeKeyboardStructuralCertification.verify(seqCert.frame, seqCert.proof, node, label, listOf("ㄱ", "ㅏ", "ㄴ")).crop!!
            }
        }
        val sixLabels = listOf("ㄱ", "ㅏ", "Space", "ㄴ", "ㅏ", "Space")
        val proofSteps = sixLabels.map { label ->
            val node = keyByLabel.getValue(label)
            AutonomousCertifiedKeyStep(
                label = label, nodeId = node.id, imageOnly = node.keyboardImageCandidate,
                crop = cropByLabel.getValue(label),
                method = if (node.keyboardImageCandidate) "space_glyph" else "native_structural_and_live_surface",
            )
        }
        val seqStepsJson = buildJsonArray {
            for (s in proofSteps) {
                add(buildJsonObject {
                    put("label", s.label); put("node_id", s.nodeId)
                    put("image_rect", JsonArray(listOf(s.crop.left, s.crop.top, s.crop.right, s.crop.bottom).map(::JsonPrimitive)))
                })
            }
        }
        val sharedBudget = SharedQaRecoveryBudget(maxAttempts = 3)
        injectNativeValidationChangeBeforeKey = 2 // key #3 (Space) hits NATIVE_ACTION_VALIDATION + DISPLAY_WINDOW_CHANGED
        val seqProof = AutonomousKeyboardSequenceProof(
            nonce = "seq-nonce-1", sessionId = session.id, sessionEpoch = session.epoch,
            observationId = seqObsId, visualId = seqVisId, steps = proofSteps,
        )
        val seqRes = control.visualKeySequenceAutonomous(buildJsonObject {
            put("session_id", session.id); put("observation_id", seqObsId); put("visual_id", seqVisId)
            put("visual_target_confirmed", false); put("text", "가 나 "); put("reuse_key_map", false)
            put("steps", seqStepsJson)
        }, seqProof, recoveryBudget = sharedBudget)
        assertTrue("Expected 6-key PASS after mid-sequence recovery: ${seqRes.data}", seqRes.success)
        assertEquals(6, seqRes.data["completed_keys"]?.jsonPrimitive?.int)
        assertEquals("가 나 ", editorText)
        assertEquals(1, sharedBudget.used)
        assertEquals(7, dispatchedNonces.distinct().size) // 1 switch + 6 keys, zero replays!

        // 5. PRE_GESTURE + DISPLAY_WINDOW_CHANGED MUST hard-stop with 0 recoveries!
        editorText = ""
        displayGen++
        val preObs = control.observe(includeImage = true, imageScope = "keyboard", keyboardSequenceLabels = seqLabels)
        val preObsId = preObs.data["observation_id"]!!.jsonPrimitive.content
        val preVisId = preObs.data["visual"]!!.jsonObject["visual_id"]!!.jsonPrimitive.content
        val preBudget = SharedQaRecoveryBudget(maxAttempts = 3)
        injectPreGestureChangeBeforeKey = 0
        val preProof = seqProof.copy(nonce = "seq-nonce-pre", observationId = preObsId, visualId = preVisId)
        val preRes = control.visualKeySequenceAutonomous(buildJsonObject {
            put("session_id", session.id); put("observation_id", preObsId); put("visual_id", preVisId)
            put("visual_target_confirmed", false); put("text", "가 나 "); put("reuse_key_map", false)
            put("steps", seqStepsJson)
        }, preProof, recoveryBudget = preBudget)
        assertFalse(preRes.success)
        assertEquals(VISUAL_SOURCE_REFUSED, preRes.data["reason_code"]?.jsonPrimitive?.content)
        assertEquals(0, preBudget.used)
        assertEquals(false, preRes.data["action_attempted"]?.jsonPrimitive?.booleanOrNull)

        // 6. UNKNOWN after partial progress MUST hard-stop with zero replay!
        editorText = ""
        displayGen++
        val unkObs = control.observe(includeImage = true, imageScope = "keyboard", keyboardSequenceLabels = seqLabels)
        val unkObsId = unkObs.data["observation_id"]!!.jsonPrimitive.content
        val unkVisId = unkObs.data["visual"]!!.jsonObject["visual_id"]!!.jsonPrimitive.content
        val noncesBeforeUnknown = dispatchedNonces.size
        injectUnknownAtKey = 1 // key #1 succeeds ("ㄱ"), key #2 returns attempted=true, acknowledged=false (UNKNOWN)
        val unkRes = control.visualKeySequenceAutonomous(buildJsonObject {
            put("session_id", session.id); put("observation_id", unkObsId); put("visual_id", unkVisId)
            put("visual_target_confirmed", false); put("text", "가 나 "); put("reuse_key_map", false)
            put("steps", seqStepsJson)
        }, seqProof.copy(nonce = "seq-nonce-unk", observationId = unkObsId, visualId = unkVisId))
        assertFalse(unkRes.success)
        assertEquals(true, unkRes.data["outcome_unknown"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(1, unkRes.data["completed_keys"]?.jsonPrimitive?.int)
        assertEquals(noncesBeforeUnknown + 2, dispatchedNonces.size) // exactly key 1 + failed key 2, zero replay!

        // 7. Reusing consumed nonce or stale visualId fails closed!
        val staleRes = control.visualKeySequenceAutonomous(buildJsonObject {
            put("session_id", session.id); put("observation_id", unkObsId); put("visual_id", unkVisId)
            put("visual_target_confirmed", false); put("text", "가 나 "); put("reuse_key_map", false)
            put("steps", seqStepsJson)
        }, seqProof.copy(nonce = "seq-nonce-unk", observationId = unkObsId, visualId = unkVisId))
        assertFalse(staleRes.success)

        // Clean end only after work completes
        assertEquals(ControlSession.State.ACTIVE, session.state)
        session.end()
        assertEquals(ControlSession.State.ENDED, session.state)
    }

    @Test
    fun sharedQaRecoveryBudget_enforcesAttemptCapAndWallClockCeiling() {
        var nowMs = 1_000L
        val budget = SharedQaRecoveryBudget(maxAttempts = 3, maxElapsedMs = 15_000L, clockMs = { nowMs })
        assertTrue(budget.tryClaim())
        assertEquals(1, budget.used)
        nowMs += 5_000L
        assertTrue(budget.tryClaim())
        assertEquals(2, budget.used)
        // Wall-clock exceeds 15_000ms -> 3rd attempt blocked!
        nowMs += 10_001L
        assertFalse(budget.withinWindow())
        assertFalse(budget.tryClaim())
        assertEquals(2, budget.used)

        // Fresh budget within window caps strictly at 3
        val budget2 = SharedQaRecoveryBudget(maxAttempts = 3, maxElapsedMs = 15_000L, clockMs = { 100L })
        assertTrue(budget2.tryClaim())
        assertTrue(budget2.tryClaim())
        assertTrue(budget2.tryClaim())
        assertFalse(budget2.tryClaim())
        assertEquals(3, budget2.used)
        assertEquals(0, budget2.remaining())
    }
}
