package ai.closepaw.chatgpt.control

import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Offline unit & integration verification of the debug-only autonomous hybrid
 * keyboard contract (v3 design). Galaxy SM-S926N geometry fixtures mirror observed
 * HoneyBoard coordinates (1440x3120 -> 443x960); live on-device Samsung HoneyBoard
 * confirmation remains UNVERIFIED_ON_DEVICE until a user-approved device run.
 */
class NativeKeyboardStructuralCertificationTest {
    private val chrome = "com.android.chrome"
    private val imePkg = "com.samsung.android.honeyboard"
    private val ime = ControlKeyboard(8411, imePkg, "$imePkg/.service.HoneyBoardService", true, true)
    private val imeRect = TapBounds(0, 1798, 1440, 3120)
    private val nowMs = 1_700_000_000_000L

    private fun editor(text: String = "", sel: Int = text.length) = ControlNode(
        id = 1, resourceId = "$chrome:id/url_bar", className = "android.widget.EditText",
        editable = true, enabled = true, focused = true, focusable = true,
        text = text, empty = text.isEmpty(), selectionStart = sel, selectionEnd = sel,
        sourceWindowId = 8464, sourcePackage = chrome, bounds = "224 127 1076 323",
        nativeEditorId = "chrome-url-1",
    )

    private fun proof(
        id: String = "vis-kbd-1",
        at: Long = nowMs - 200L,
        scope: String = "keyboard",
        displayId: Int = 0,
        imeWindowId: Int = ime.windowId,
        regions: List<TapBounds> = listOf(imeRect),
        disclosureAllowed: () -> Boolean = { true },
    ) = VisualTargetProof(
        image = ScreenImage(443, 960, "image/jpeg", byteArrayOf(1, 2, 3), ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
        pixelHash = "hash-$id",
        window = TapBounds(0, 0, 1440, 3120),
        context = "ctx-1",
        at = at,
        id = id,
        sourceWindowId = imeWindowId,
        scope = scope,
        sourceDisplayId = displayId,
        sourceImeWindowId = imeWindowId,
        displayGeneration = 5L,
        imeBounds = "0 1798 1440 3120",
        disclosedRegions = regions,
        sourceDisclosureAllowed = disclosureAllowed,
    )

    private fun hangulKey(
        id: Int,
        label: String,
        left: Int,
        top: Int,
        width: Int = 118,
        height: Int = 155,
        parent: Int = 200,
        visualKeyChildCount: Int = 1,
        textEntryKey: Boolean = false,
        clickable: Boolean = false,
        sourcePackage: String = imePkg,
        sourceWindowId: Int = ime.windowId,
    ) = ControlNode(
        id = id, text = label, keyboardKey = label,
        className = "android.view.ViewGroup",
        bounds = "$left $top ${left + width} ${top + height}",
        parent = parent, enabled = true, clickable = clickable, textEntryKey = textEntryKey,
        sourceWindowId = sourceWindowId, sourcePackage = sourcePackage,
        visualKeyCandidate = true, visualKeyChildCount = visualKeyChildCount,
    )

    /**
     * Realistic Galaxy SM-S926N 2-beolsik fixture (1440x3120):
     * Row 1 (y=2154..2309): ㅂ ㅈ ㄷ ㄱ ㅅ ㅛ (ㄱ at 451..569, matches KeyboardSequencePixelProofTest)
     * Row 2 (y=2354..2509): ㅁ ㄴ ㅇ ㄹ ㅎ ㅗ ㅓ ㅏ (ㄴ at 241..359, ㅏ at 1081..1199)
     * Bottom row (y=2754..2909): ',' + Space (image-only, 503..1059) + '.'
     */
    private fun galaxyKoreanNodes(widthJitterPx: Int = 0): List<ControlNode> {
        val row1Labels = listOf("ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ", "ㅛ")
        val row1Lefts = listOf(31, 171, 311, 451, 591, 731)
        val row1 = row1Labels.mapIndexed { idx, label ->
            val w = 118 + if (idx % 2 == 1) widthJitterPx else 0
            hangulKey(100 + idx, label, row1Lefts[idx], 2154, width = w, height = 155, parent = 201)
        }
        val row2Labels = listOf("ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ")
        val row2Lefts = listOf(101, 241, 381, 521, 661, 801, 941, 1081)
        val row2 = row2Labels.mapIndexed { idx, label ->
            val w = 118 + if (idx % 2 == 1) widthJitterPx else 0
            hangulKey(120 + idx, label, row2Lefts[idx], 2354, width = w, height = 155, parent = 202)
        }
        val leftNeighbor = hangulKey(150, ",", 370, 2754, width = 118, height = 155, parent = 203, clickable = true, textEntryKey = true)
        val space = ControlNode(
            id = 151, parent = 203, className = "android.view.ViewGroup",
            bounds = "503 2754 1059 2909", enabled = true,
            sourceWindowId = ime.windowId, sourcePackage = imePkg,
            keyboardImageCandidate = true, visualKeyChildCount = 0,
        )
        val rightNeighbor = hangulKey(152, ".", 1070, 2754, width = 118, height = 155, parent = 203, clickable = true, textEntryKey = true)
        return row1 + row2 + listOf(leftNeighbor, space, rightNeighbor)
    }

    private fun koreanFrame(
        nodes: List<ControlNode> = galaxyKoreanNodes(),
        ed: ControlNode = editor(),
        p: VisualTargetProof = proof(),
        keyboard: ControlKeyboard? = ime,
        truncated: Boolean = false,
        reason: String? = null,
    ) = ControlFrame(
        packageName = chrome, windowId = 8464, fingerprint = "fp-${ed.text}-${nodes.size}",
        nodes = listOf(ed) + nodes, reason = reason, truncated = truncated,
        touchContext = "touch-ctx-1", foregroundWindowId = 8464, windowContext = "win-ctx-1",
        keyboard = keyboard, inputTargetId = ed.id, inputTarget = ed, visual = p,
    )

    @Test fun normalGalaxyKoreanKeysAndDpiRoundingPassStructuralAndLiveSurfaceChecks() {
        for (jitter in listOf(0, 1, 2)) {
            val nodes = galaxyKoreanNodes(widthJitterPx = jitter)
            val p = proof()
            val f = koreanFrame(nodes = nodes, p = p)
            val targets = listOf("ㄱ", "ㅏ", "ㄴ")
            for (label in targets) {
                val node = nodes.single { it.keyboardKey == label }
                val res = NativeKeyboardStructuralCertification.verify(f, p, node, label, targets, nowMs)
                assertNotNull("Expected $label (jitter=$jitter) to certify, got ${res.failureStage}", res.crop)
                assertNull(res.failureStage)
                val mapped = p.map(res.crop!!)
                assertNotNull(mapped)
                assertTrue(KeyboardImageFallbackPolicy.bounds(node.bounds)!!.contains(mapped!!))
            }
        }

        // Crop-scoped live surface liveness: real keycap + ink passes, uniform/blank fails
        val liveKeyPixels = IntArray(29 * 36) { idx -> if (idx % 29 in 8..20 && idx / 29 in 10..12) 24 else 246 }
        val blankWhite = IntArray(29 * 36) { 246 }
        val blankBlack = IntArray(29 * 36) { 0 }
        val lowContrast = IntArray(29 * 36) { idx -> if (idx < 20) 225 else 246 } // diff = 21 < 24
        assertTrue(NativeKeyboardStructuralCertification.hasLiveSurfaceContrast(liveKeyPixels))
        assertFalse(NativeKeyboardStructuralCertification.hasLiveSurfaceContrast(blankWhite))
        assertFalse(NativeKeyboardStructuralCertification.hasLiveSurfaceContrast(blankBlack))
        assertFalse(NativeKeyboardStructuralCertification.hasLiveSurfaceContrast(lowContrast))
        assertFalse(NativeKeyboardStructuralCertification.hasLiveSurfaceContrast(IntArray(0)))
    }

    @Test fun rejectsNativeLabelAloneWrongRoleSuggestionsDuplicatesWrongLayoutAndMasking() {
        val p = proof()
        val fullNodes = galaxyKoreanNodes()
        val gNode = fullNodes.single { it.keyboardKey == "ㄱ" }

        // 1) Native label alone on detached node (no physical row) -> rejected
        val detachedOnly = koreanFrame(nodes = listOf(gNode), p = p)
        assertEquals("native_physical_row_unverified",
            NativeKeyboardStructuralCertification.verify(detachedOnly, p, gNode, "ㄱ", listOf("ㄱ"), nowMs).failureStage)

        // 2) Wrong key roles: SUBMIT (Enter), LAYOUT (한/영), image-only Space -> rejected
        val enterNode = hangulKey(301, "Enter", 451, 2154, parent = 201, clickable = true, textEntryKey = true)
        val layoutNode = hangulKey(302, "한/영", 451, 2154, parent = 201, clickable = true, textEntryKey = true)
        val spaceNode = fullNodes.single { it.keyboardImageCandidate }
        assertEquals("native_character_role_unverified",
            NativeKeyboardStructuralCertification.verify(koreanFrame(fullNodes + enterNode, p = p), p, enterNode, "Enter", listOf("Enter"), nowMs).failureStage)
        assertEquals("native_character_role_unverified",
            NativeKeyboardStructuralCertification.verify(koreanFrame(fullNodes + layoutNode, p = p), p, layoutNode, "한/영", listOf("한/영"), nowMs).failureStage)
        assertEquals("image_only_requires_pixel_role",
            NativeKeyboardStructuralCertification.verify(koreanFrame(fullNodes, p = p), p, spaceNode, "Space", listOf("Space"), nowMs).failureStage)

        // 3) Ambiguous duplicate ㄱ key -> rejected
        val gDup = gNode.copy(id = 999, bounds = "871 2154 989 2309")
        assertEquals("native_candidate_not_unique",
            NativeKeyboardStructuralCertification.verify(koreanFrame(fullNodes + gDup, p = p), p, gNode, "ㄱ", listOf("ㄱ", "ㅏ", "ㄴ"), nowMs).failureStage)

        // 4) Standalone suggestion strip node with label "ㄱ" -> rejected
        val suggestion = ControlNode(
            id = 401, text = "ㄱ", keyboardKey = "ㄱ", className = "android.widget.TextView",
            bounds = "100 1850 420 1930", parent = 300, enabled = true, clickable = true,
            sourceWindowId = ime.windowId, sourcePackage = imePkg, visualKeyCandidate = true, visualKeyChildCount = 0,
        )
        val noRealG = fullNodes.filterNot { it.keyboardKey == "ㄱ" } + suggestion
        assertNotNull(NativeKeyboardStructuralCertification.verify(koreanFrame(noRealG, p = p), p, suggestion, "ㄱ", listOf("ㄱ"), nowMs).failureStage)

        // 5) Wrong layout: single-row only -> rejected
        val row1Only = fullNodes.filter { it.parent == 201 }
        assertEquals("native_multirow_layout_unverified",
            NativeKeyboardStructuralCertification.verify(koreanFrame(row1Only, p = p), p, gNode, "ㄱ", listOf("ㄱ"), nowMs).failureStage)

        // 5b) Wrong layout: mixed Latin letters inside physical rows -> rejected
        val latinRow2 = listOf("a", "s", "d", "f", "g", "h").mapIndexed { i, ch ->
            hangulKey(500 + i, ch, 101 + i * 140, 2354, parent = 202)
        }
        val mixed = fullNodes.filter { it.parent == 201 } + latinRow2
        assertEquals("native_layout_script_unverified",
            NativeKeyboardStructuralCertification.verify(koreanFrame(mixed, p = p), p, gNode, "ㄱ", listOf("ㄱ"), nowMs).failureStage)

        // 5c) Malformed / non-parented layout (parent = -1) -> rejected
        val unparented = fullNodes.map { it.copy(parent = -1) }
        val gUnparented = unparented.single { it.keyboardKey == "ㄱ" }
        assertEquals("native_key_shape_unverified",
            NativeKeyboardStructuralCertification.verify(koreanFrame(unparented, p = p), p, gUnparented, "ㄱ", listOf("ㄱ"), nowMs).failureStage)

        // 6) Outside IME bounds & overlapping neighbor at interior -> rejected
        val outsideG = gNode.copy(bounds = "451 1600 569 1755")
        assertEquals("node_outside_ime_or_viewport",
            NativeKeyboardStructuralCertification.verify(
                koreanFrame(fullNodes.filterNot { it.id == gNode.id } + outsideG, p = p), p, outsideG, "ㄱ", listOf("ㄱ"), nowMs,
            ).failureStage)
        val overlappingNeighbor = hangulKey(601, "ㅎ", 451, 2154, parent = 299)
        assertNotNull(NativeKeyboardStructuralCertification.verify(
            koreanFrame(fullNodes + overlappingNeighbor, p = p), p, gNode, "ㄱ", listOf("ㄱ"), nowMs,
        ).failureStage)

        // 7) Authority / IME / owner / mask / expiry / truncation -> rejected
        assertEquals("frame_incomplete_or_refused",
            NativeKeyboardStructuralCertification.verify(koreanFrame(fullNodes, p = p, truncated = true), p, gNode, "ㄱ", listOf("ㄱ"), nowMs).failureStage)
        assertEquals("ime_not_authorized",
            NativeKeyboardStructuralCertification.verify(koreanFrame(fullNodes, p = p, keyboard = ime.copy(interactAllowed = false)), p, gNode, "ㄱ", listOf("ㄱ"), nowMs).failureStage)
        assertEquals("ime_identity_unverified",
            NativeKeyboardStructuralCertification.verify(koreanFrame(fullNodes, p = p, keyboard = ime.copy(inputMethodId = "other/.Ime")), p, gNode, "ㄱ", listOf("ㄱ"), nowMs).failureStage)
        assertEquals("proof_disclosure_revoked",
            NativeKeyboardStructuralCertification.verify(koreanFrame(fullNodes, p = proof(disclosureAllowed = { false })), proof(disclosureAllowed = { false }), gNode, "ㄱ", listOf("ㄱ"), nowMs).failureStage)
        assertEquals("proof_age_expired",
            NativeKeyboardStructuralCertification.verify(koreanFrame(fullNodes, p = proof(at = nowMs - 11_000L)), proof(at = nowMs - 11_000L), gNode, "ㄱ", listOf("ㄱ"), nowMs).failureStage)
        assertEquals("proof_owner_mismatch",
            NativeKeyboardStructuralCertification.verify(koreanFrame(fullNodes, p = proof(imeWindowId = 9999)), proof(imeWindowId = 9999), gNode, "ㄱ", listOf("ㄱ"), nowMs).failureStage)
        // Null or mismatched frame.visual must fail closed (never accept unbound frame)
        assertEquals("frame_visual_id_mismatch",
            NativeKeyboardStructuralCertification.verify(koreanFrame(fullNodes, p = p).copy(visual = null), p, gNode, "ㄱ", listOf("ㄱ"), nowMs).failureStage)
        assertEquals("frame_visual_id_mismatch",
            NativeKeyboardStructuralCertification.verify(koreanFrame(fullNodes, p = proof(id = "vis-other")), p, gNode, "ㄱ", listOf("ㄱ"), nowMs).failureStage)
        val maskedG = proof(regions = listOf(TapBounds(0, 1798, 400, 3120)))
        assertEquals("native_physical_row_unverified",
            NativeKeyboardStructuralCertification.verify(koreanFrame(fullNodes, p = maskedG), maskedG, gNode, "ㄱ", listOf("ㄱ"), nowMs).failureStage)
    }

    @Test fun autonomousSequenceLedgerEnforcesSingleActiveMonotonicStepsAndSingleUseNonces() {
        val ledger = AutonomousSequenceLedger()
        assertFalse(ledger.begin("", "s1", 1L, 6))
        assertFalse(ledger.begin("seq-1", "s1", 1L, 0))
        assertTrue(ledger.begin("seq-1", "s1", 1L, 2))
        // Overlapping sequence rejected
        assertFalse(ledger.begin("seq-2", "s1", 1L, 2))

        fun ctx(nonce: String, seq: String = "seq-1", step: Int = 0, epoch: Long = 1L) =
            AutonomousKeyboardDispatchContext(nonce, seq, "s1", epoch, "obs-1", "vis-1", step, 100, "ㄱ", false, setOf("ㄱ"))

        // Wrong sequence nonce / epoch / out-of-order step rejected WITHOUT consuming nonce
        assertFalse(ledger.verifyAndConsumeDispatch(ctx("n-bad", seq = "seq-other", step = 0)))
        assertFalse(ledger.verifyAndConsumeDispatch(ctx("n-bad", epoch = 2L, step = 0)))
        assertFalse(ledger.verifyAndConsumeDispatch(ctx("n-step1-early", step = 1)))

        // Step 0 succeeds once; replay of same nonce fails!
        assertTrue(ledger.verifyAndConsumeDispatch(ctx("n-0a", step = 0)))
        assertFalse(ledger.verifyAndConsumeDispatch(ctx("n-0a", step = 0)))
        // Confirmed no-touch retry of step 0 with a NEW nonce succeeds before advance()
        assertTrue(ledger.verifyAndConsumeDispatch(ctx("n-0b", step = 0)))

        // Advance to step 1 -> step 0 now rejected, step 1 succeeds once
        ledger.advance("seq-1", 0)
        assertFalse(ledger.verifyAndConsumeDispatch(ctx("n-0c", step = 0)))
        assertTrue(ledger.verifyAndConsumeDispatch(ctx("n-1", step = 1)))

        // End clears active sequence; prior consumed nonce remains unreplayable even in a new sequence
        ledger.end("seq-1")
        assertFalse(ledger.verifyAndConsumeDispatch(ctx("n-after-end", step = 1)))
        assertTrue(ledger.begin("seq-2", "s1", 1L, 2))
        assertFalse(ledger.verifyAndConsumeDispatch(ctx("n-0a", seq = "seq-2", step = 0)))
    }

    @Test fun endToEndAutonomousHybridSequenceEnforcesContractSeparationDebugGateAndLiveRecheck() = runBlocking {
        class HybridPort(override val allowsAutonomousDebugKeyboardProof: Boolean) : ControlPort {
            val activeSession = ControlSession().apply { start() }
            val ledger = AutonomousSequenceLedger()
            var currentText = ""
            var proofCounter = 0
            var breakLayoutOnRenew = false
            var spaceRoleVerified = true
            val seenVisualTargetConfirmed = mutableListOf<Boolean?>()
            val seenStepNonces = mutableListOf<String>()
            val seenImageLabels = mutableListOf<String?>()

            override val validatesAtNativeBoundary = true
            override fun session() = activeSession
            override fun beginAutonomousKeyboardSequence(sequenceNonce: String, sessionId: String, epoch: Long, totalSteps: Int) =
                allowsAutonomousDebugKeyboardProof && ledger.begin(sequenceNonce, sessionId, epoch, totalSteps)
            override fun advanceAutonomousKeyboardStep(sequenceNonce: String, completedStepIndex: Int) {
                if (allowsAutonomousDebugKeyboardProof) ledger.advance(sequenceNonce, completedStepIndex)
            }
            override fun endAutonomousKeyboardSequence(sequenceNonce: String) = ledger.end(sequenceNonce)
            override suspend fun apps(filter: String) = JsonArray(emptyList())

            override suspend fun capture(): ControlFrame {
                val nodes = if (breakLayoutOnRenew && currentText.isNotEmpty()) {
                    galaxyKoreanNodes().filter { it.parent == 201 } // breaks multi-row invariant on renewal!
                } else {
                    galaxyKoreanNodes()
                }
                return koreanFrame(nodes = nodes, ed = editor(currentText, currentText.length), p = proof("vis-cap"))
                    .copy(visual = null)
            }

            override suspend fun visualKeyboard(frame: ControlFrame): VisualTargetProof {
                proofCounter++
                return proof(id = "vis-$proofCounter", at = nowMs)
            }

            override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
                val action = input["action"]?.jsonPrimitive?.content.orEmpty()
                val proof = frame.visual ?: return ControlEffect(false, false, "visual_observation_required")
                val imageLabel = (input["image_key_label"] as? JsonPrimitive)?.contentOrNull
                val imageOnly = action == "visual_key" && node?.keyboardImageCandidate == true
                val ctx = currentCoroutineContext()[AutonomousKeyboardDispatchContext]
                val external = input["visual_target_confirmed"]?.jsonPrimitive?.booleanOrNull == true
                seenVisualTargetConfirmed += input["visual_target_confirmed"]?.jsonPrimitive?.booleanOrNull
                seenImageLabels += imageLabel
                if (ctx != null && external) return ControlEffect(false, false, "visual_proof_invalid_or_expired")
                val labelOk = if (imageOnly) imageLabel == ctx?.label else imageLabel == null && node?.keyboardKey == ctx?.label
                val fieldsMatch = action == "visual_key" && !external && allowsAutonomousDebugKeyboardProof &&
                    ctx != null && input["observation_id"]?.jsonPrimitive?.contentOrNull == ctx.observationId &&
                    ctx.visualId == proof.id && ctx.sessionId == activeSession.id && ctx.epoch == activeSession.epoch &&
                    node != null && ctx.nodeId == node.id && ctx.imageOnly == imageOnly && labelOk
                val autonomousOk = ctx != null && fieldsMatch && ledger.verifyAndConsumeDispatch(ctx)
                if (!external && !autonomousOk) return ControlEffect(false, false, "visual_proof_invalid_or_expired")
                if (ctx != null) seenStepNonces += ctx.nonce
                if (imageOnly && !spaceRoleVerified) return ControlEffect(false, false, "keyboard_image_label_unverified")
                val pressed = if (imageOnly) imageLabel.orEmpty() else node?.keyboardKey.orEmpty()
                currentText = if (pressed == "Space") "$currentText " else HangulKeyComposer.append(currentText, pressed)
                return ControlEffect(true, true, "visual_key_acknowledged")
            }
        }

        // 1) Release mode (allowsAutonomousDebugKeyboardProof = false): certifiedKeyboardObservation & autonomous sequence BLOCKED
        run {
            val port = HybridPort(allowsAutonomousDebugKeyboardProof = false)
            val control = GenericControl(port, { true }, { nowMs })
            val obs = control.observe(includeImage = true, imageScope = "keyboard", keyboardSequenceLabels = listOf("ㄱ", "ㅏ", "ㄴ", "Space"))
            assertTrue(obs.success)
            val obsId = obs.data["observation_id"]!!.jsonPrimitive.content
            val visId = obs.data["visual"]!!.jsonObject["visual_id"]!!.jsonPrimitive.content
            assertNull(control.certifiedKeyboardObservation(obsId, visId, port.activeSession.epoch))

            val blocked = control.visualKeySequenceAutonomous(
                buildJsonObject {
                    put("session_id", port.activeSession.id)
                    put("observation_id", obsId)
                    put("visual_id", visId)
                    put("visual_target_confirmed", false)
                    put("text", "가 나 ")
                    put("reuse_key_map", false)
                    put("steps", JsonArray(emptyList()))
                },
                AutonomousKeyboardSequenceProof("seq-rel", port.activeSession.id, port.activeSession.epoch, obsId, visId, emptyList()),
            )
            assertFalse(blocked.success)
            assertEquals("autonomous_keyboard_proof_not_allowed", blocked.data["reason_code"]?.jsonPrimitive?.content)
            assertEquals(false, blocked.data["action_attempted"]?.jsonPrimitive?.boolean)
        }

        // 2) External MCP visualKeySequence rejects visual_target_confirmed=false
        run {
            val port = HybridPort(allowsAutonomousDebugKeyboardProof = true)
            val control = GenericControl(port, { true }, { nowMs })
            val obs = control.observe(includeImage = true, imageScope = "keyboard", keyboardSequenceLabels = listOf("ㄱ", "ㅏ", "ㄴ", "Space"))
            val obsId = obs.data["observation_id"]!!.jsonPrimitive.content
            val visId = obs.data["visual"]!!.jsonObject["visual_id"]!!.jsonPrimitive.content
            val extRefused = control.visualKeySequence(buildJsonObject {
                put("session_id", port.activeSession.id)
                put("observation_id", obsId)
                put("visual_id", visId)
                put("visual_target_confirmed", false)
                put("text", "가 나 ")
                put("steps", JsonArray(emptyList()))
            })
            assertFalse(extRefused.success)
            assertEquals("keyboard_visual_proof_unavailable", extRefused.data["reason_code"]?.jsonPrimitive?.content)
            assertEquals("image_not_confirmed", extRefused.data["initial_proof_reason_code"]?.jsonPrimitive?.content)
            assertEquals("external_visual_review", extRefused.data["confirmation_mode"]?.jsonPrimitive?.content)
            assertEquals(false, extRefused.data["external_visual_review_confirmed"]?.jsonPrimitive?.boolean)
        }

        // 3) Debug autonomous hybrid 6-key sequence PASS with explicit honest evidence & unique step nonces
        run {
            val port = HybridPort(allowsAutonomousDebugKeyboardProof = true)
            val control = GenericControl(port, { true }, { nowMs })
            val obs = control.observe(includeImage = true, imageScope = "keyboard", keyboardSequenceLabels = listOf("ㄱ", "ㅏ", "ㄴ", "Space"))
            assertTrue(obs.success)
            val obsId = obs.data["observation_id"]!!.jsonPrimitive.content
            val visId = obs.data["visual"]!!.jsonObject["visual_id"]!!.jsonPrimitive.content
            val certified = control.certifiedKeyboardObservation(obsId, visId, port.activeSession.epoch)
            assertNotNull(certified)
            // Wrong observationId / visualId / epoch fails closed
            assertNull(control.certifiedKeyboardObservation("wrong-obs", visId, port.activeSession.epoch))
            assertNull(control.certifiedKeyboardObservation(obsId, "wrong-vis", port.activeSession.epoch))
            assertNull(control.certifiedKeyboardObservation(obsId, visId, port.activeSession.epoch + 1))

            val seqLabels = listOf("ㄱ", "ㅏ", "Space", "ㄴ", "ㅏ", "Space")
            val nativeTargets = listOf("ㄱ", "ㅏ", "ㄴ")
            val proofSteps = mutableListOf<AutonomousCertifiedKeyStep>()
            val jsonSteps = mutableListOf<JsonObject>()
            for (label in seqLabels) {
                val node = if (label == "Space") certified!!.frame.nodes.single { it.keyboardImageCandidate }
                else certified!!.frame.nodes.single { it.keyboardKey == label }
                val crop = if (label == "Space") {
                    val b = KeyboardImageFallbackPolicy.bounds(node.bounds)!!
                    KeyboardImageFallbackPolicy.crop(certified.proof, b)!!
                } else {
                    NativeKeyboardStructuralCertification.verify(certified.frame, certified.proof, node, label, nativeTargets, nowMs).crop!!
                }
                val method = if (label == "Space") "space_glyph" else "native_structural_and_live_surface"
                proofSteps += AutonomousCertifiedKeyStep(label, node.id, label == "Space", crop, method)
                jsonSteps += buildJsonObject {
                    put("label", label)
                    put("node_id", node.id)
                    put("image_rect", JsonArray(listOf(crop.left, crop.top, crop.right, crop.bottom).map(::JsonPrimitive)))
                }
            }
            val autonomousProof = AutonomousKeyboardSequenceProof("seq-debug-1", port.activeSession.id, port.activeSession.epoch, obsId, visId, proofSteps)

            // Conflict check: passing visual_target_confirmed=true with autonomousProof is refused!
            val conflict = control.visualKeySequenceAutonomous(
                buildJsonObject {
                    put("session_id", port.activeSession.id)
                    put("observation_id", obsId)
                    put("visual_id", visId)
                    put("visual_target_confirmed", true)
                    put("text", "가 나 ")
                    put("reuse_key_map", false)
                    put("steps", JsonArray(jsonSteps))
                },
                autonomousProof,
            )
            assertFalse(conflict.success)
            assertEquals("autonomous_confirmation_mode_conflict", conflict.data["reason_code"]?.jsonPrimitive?.content)

            // Re-observe cleanly and run real autonomous hybrid 6-key sequence!
            val obs2 = control.observe(includeImage = true, imageScope = "keyboard", keyboardSequenceLabels = listOf("ㄱ", "ㅏ", "ㄴ", "Space"))
            val obsId2 = obs2.data["observation_id"]!!.jsonPrimitive.content
            val visId2 = obs2.data["visual"]!!.jsonObject["visual_id"]!!.jsonPrimitive.content
            val autonomousProof2 = autonomousProof.copy(nonce = "seq-debug-2", observationId = obsId2, visualId = visId2)
            val ok = control.visualKeySequenceAutonomous(
                buildJsonObject {
                    put("session_id", port.activeSession.id)
                    put("observation_id", obsId2)
                    put("visual_id", visId2)
                    put("visual_target_confirmed", false)
                    put("text", "가 나 ")
                    put("reuse_key_map", false)
                    put("steps", JsonArray(jsonSteps))
                },
                autonomousProof2,
            )
            assertTrue("Expected autonomous hybrid sequence PASS, got ${ok.data}", ok.success)
            assertEquals("가 나 ", port.currentText)
            assertEquals(6, ok.data["completed_keys"]?.jsonPrimitive?.int)
            assertEquals(2, ok.data["confirmed_image_fallback_spaces"]?.jsonPrimitive?.int)
            assertEquals("autonomous_debug_hybrid_structural_and_image", ok.data["confirmation_mode"]?.jsonPrimitive?.content)
            assertEquals(false, ok.data["external_visual_review_confirmed"]?.jsonPrimitive?.boolean)
            assertEquals(false, ok.data["native_keys_semantically_ocr_verified"]?.jsonPrimitive?.boolean)
            assertEquals(true, ok.data["image_only_keys_pixel_role_verified"]?.jsonPrimitive?.boolean)
            assertEquals(List(6) { false }, port.seenVisualTargetConfirmed)
            assertEquals(6, port.seenStepNonces.distinct().size)
            assertEquals(listOf(null, null, "Space", null, null, "Space"), port.seenImageLabels)
            // Ledger cleaned in finally -> new sequence can start cleanly
            assertTrue(port.ledger.begin("seq-after-cleanup", port.activeSession.id, port.activeSession.epoch, 2))
        }

        // 4) Renewed frame breaking multi-row layout after key 1 -> halts at key 2 before touching!
        run {
            val port = HybridPort(allowsAutonomousDebugKeyboardProof = true).apply { breakLayoutOnRenew = true }
            val control = GenericControl(port, { true }, { nowMs })
            val obs = control.observe(includeImage = true, imageScope = "keyboard", keyboardSequenceLabels = listOf("ㄱ", "ㅏ", "ㄴ", "Space"))
            val obsId = obs.data["observation_id"]!!.jsonPrimitive.content
            val visId = obs.data["visual"]!!.jsonObject["visual_id"]!!.jsonPrimitive.content
            val certified = control.certifiedKeyboardObservation(obsId, visId, port.activeSession.epoch)!!
            val seqLabels = listOf("ㄱ", "ㅏ", "Space", "ㄴ", "ㅏ", "Space")
            val nativeTargets = listOf("ㄱ", "ㅏ", "ㄴ")
            val proofSteps = mutableListOf<AutonomousCertifiedKeyStep>()
            val jsonSteps = mutableListOf<JsonObject>()
            for (label in seqLabels) {
                val node = if (label == "Space") certified.frame.nodes.single { it.keyboardImageCandidate }
                else certified.frame.nodes.single { it.keyboardKey == label }
                val crop = if (label == "Space") KeyboardImageFallbackPolicy.crop(certified.proof, KeyboardImageFallbackPolicy.bounds(node.bounds)!!)!!
                else NativeKeyboardStructuralCertification.verify(certified.frame, certified.proof, node, label, nativeTargets, nowMs).crop!!
                proofSteps += AutonomousCertifiedKeyStep(label, node.id, label == "Space", crop, if (label == "Space") "space_glyph" else "native_structural_and_live_surface")
                jsonSteps += buildJsonObject {
                    put("label", label); put("node_id", node.id)
                    put("image_rect", JsonArray(listOf(crop.left, crop.top, crop.right, crop.bottom).map(::JsonPrimitive)))
                }
            }
            val halted = control.visualKeySequenceAutonomous(
                buildJsonObject {
                    put("session_id", port.activeSession.id); put("observation_id", obsId)
                    put("visual_id", visId); put("visual_target_confirmed", false)
                    put("text", "가 나 "); put("reuse_key_map", false); put("steps", JsonArray(jsonSteps))
                },
                AutonomousKeyboardSequenceProof("seq-renew-break", port.activeSession.id, port.activeSession.epoch, obsId, visId, proofSteps),
            )
            assertFalse(halted.success)
            assertEquals(1, halted.data["completed_keys"]?.jsonPrimitive?.int)
            assertTrue(port.ledger.begin("seq-after-halt", port.activeSession.id, port.activeSession.epoch, 1))
        }
    }
}
