package ai.closepaw.chatgpt.control

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs

/**
 * Internal-only debug autonomous proof step. Never exposed in external MCP schemas
 * or parsed from JSON; external callers must still pass visual_target_confirmed=true.
 */
internal data class AutonomousCertifiedKeyStep(
    val label: String,
    val nodeId: Int,
    val imageOnly: Boolean,
    val crop: TapBounds,
    val method: String,
)

/**
 * Internal-only proof bundle for debug autonomous hybrid keyboard sequences.
 * Distinct from external visual review: external_visual_review_confirmed stays false.
 */
internal data class AutonomousKeyboardSequenceProof(
    val nonce: String,
    val sessionId: String,
    val sessionEpoch: Long,
    val observationId: String,
    val visualId: String,
    val steps: List<AutonomousCertifiedKeyStep>,
)

/**
 * Per-dispatch coroutine context scoped to a single autonomous visual_key invocation.
 * Carries a fresh single-use step nonce and exact target bindings verified at both
 * GenericControl.actionNative and AndroidControlPort.perform.
 */
internal data class AutonomousKeyboardDispatchContext(
    val nonce: String,
    val sequenceNonce: String,
    val sessionId: String,
    val epoch: Long,
    val observationId: String,
    val visualId: String,
    val stepIndex: Int,
    val nodeId: Int,
    val label: String,
    val imageOnly: Boolean,
    val expectedNativeLabels: Set<String>,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AutonomousKeyboardDispatchContext>
}

/**
 * Thread-safe single-active-sequence ledger and single-use step-nonce tracker for
 * debug autonomous keyboard dispatch. Prevents overlapping sequences, out-of-order
 * steps, stale sequence nonces, and step-nonce replay.
 */
internal class AutonomousSequenceLedger {
    private data class Active(
        val sequenceNonce: String,
        val sessionId: String,
        val epoch: Long,
        val totalSteps: Int,
        var expectedStepIndex: Int = 0,
    )

    private var active: Active? = null
    private val consumedStepNonces = LinkedHashSet<String>()

    @Synchronized
    fun begin(sequenceNonce: String, sessionId: String, epoch: Long, totalSteps: Int): Boolean {
        if (sequenceNonce.isBlank() || sessionId.isBlank() || totalSteps !in 1..6 || active != null) {
            return false
        }
        active = Active(sequenceNonce, sessionId, epoch, totalSteps)
        return true
    }

    @Synchronized
    fun advance(sequenceNonce: String, completedStepIndex: Int) {
        val current = active ?: return
        if (current.sequenceNonce == sequenceNonce && current.expectedStepIndex == completedStepIndex) {
            current.expectedStepIndex = completedStepIndex + 1
        }
    }

    @Synchronized
    fun end(sequenceNonce: String) {
        if (active?.sequenceNonce == sequenceNonce) {
            active = null
        }
    }

    @Synchronized
    fun clearActive() {
        active = null
    }

    /**
     * Atomically validates active sequence nonce, session/epoch, monotonic step index,
     * and consumes the single-use step nonce strictly LAST.
     */
    @Synchronized
    fun verifyAndConsumeDispatch(ctx: AutonomousKeyboardDispatchContext): Boolean {
        val current = active ?: return false
        if (ctx.nonce.isBlank() || ctx.sequenceNonce.isBlank()) return false
        if (current.sequenceNonce != ctx.sequenceNonce) return false
        if (current.sessionId != ctx.sessionId || current.epoch != ctx.epoch) return false
        if (ctx.stepIndex !in 0 until current.totalSteps || ctx.stepIndex != current.expectedStepIndex) return false
        if (ctx.nonce in consumedStepNonces) return false
        consumedStepNonces.add(ctx.nonce)
        while (consumedStepNonces.size > 64) {
            consumedStepNonces.remove(consumedStepNonces.first())
        }
        return true
    }
}

/**
 * Fail-closed structural + owned-pixel-transform certification for unique native-labeled
 * physical IME character keys in debug autonomous QA.
 *
 * Explicitly NOT a semantic glyph recognizer and NOT equivalent to external visual review:
 * proves OS-selected IME ownership, uniqueness, suggestion-strip exclusion, membership in
 * a corroborated multi-row physical character keyboard of the active script, exact
 * screen<->image mapping, owned interior disclosure, and neighbor disjointness.
 * Image-only keys (e.g. Space) are rejected here and must use SpaceBarGlyphPolicy/OCR.
 */
internal object NativeKeyboardStructuralCertification {
    internal data class Result(
        val crop: TapBounds? = null,
        val failureStage: String? = null,
    )

    private data class Cell(val node: ControlNode, val rect: TapBounds, val label: String)
    private data class Row(
        val parent: Int,
        val top: Int,
        val bottom: Int,
        val width: Int,
        val keys: List<Cell>,
    )

    private val alphabeticScripts = setOf(
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.LATIN,
        Character.UnicodeScript.CYRILLIC,
        Character.UnicodeScript.GREEK,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.ARABIC,
        Character.UnicodeScript.HEBREW,
    )

    /** Pure crop-scoped surface liveness check: proves non-blank rendered pixels, never glyph identity. */
    fun hasLiveSurfaceContrast(luminance: IntArray, minContrast: Int = 24): Boolean {
        if (luminance.isEmpty()) return false
        var minLum = 255
        var maxLum = 0
        for (v in luminance) {
            val clamped = v.coerceIn(0, 255)
            if (clamped < minLum) minLum = clamped
            if (clamped > maxLum) maxLum = clamped
        }
        return maxLum - minLum >= minContrast
    }

    fun verify(
        frame: ControlFrame,
        proof: VisualTargetProof,
        node: ControlNode,
        expectedLabel: String,
        requiredNativeLabels: List<String> = listOf(expectedLabel),
        nowMs: Long = System.currentTimeMillis(),
    ): Result {
        if (frame.reason != null || frame.truncated || frame.navigationOnly) {
            return Result(failureStage = "frame_incomplete_or_refused")
        }
        val ime = frame.keyboard?.takeIf { it.readAllowed && it.interactAllowed }
            ?: return Result(failureStage = "ime_not_authorized")
        if (!ime.inputMethodId.contains('/') || ime.inputMethodId.substringBefore('/') != ime.packageName) {
            return Result(failureStage = "ime_identity_unverified")
        }
        if (node.keyboardImageCandidate) {
            return Result(failureStage = "image_only_requires_pixel_role")
        }
        if (!node.enabled || node.password || !node.visualKeyCandidate ||
            node.sourceWindowId != ime.windowId || node.sourcePackage != ime.packageName) {
            return Result(failureStage = "native_candidate_unverified")
        }
        if (proof.scope != "keyboard" || proof.sourceDisplayId != 0) {
            return Result(failureStage = "proof_scope_or_display_invalid")
        }
        if (!proof.sourceDisclosureAllowed()) {
            return Result(failureStage = "proof_disclosure_revoked")
        }
        if (nowMs - proof.at !in 0L..10_000L) {
            return Result(failureStage = "proof_age_expired")
        }
        if (VisualKeyboardPolicy.proofOwnerRefusal(proof, ime) != null) {
            return Result(failureStage = "proof_owner_mismatch")
        }
        val role = VisualKeyboardPolicy.identifyCandidate(node, ime)
        if (role == null || role.label != expectedLabel || role.kind != KeyboardKeyPolicy.Kind.CHARACTER) {
            return Result(failureStage = "native_character_role_unverified")
        }
        if (frame.visual?.id != proof.id) {
            return Result(failureStage = "frame_visual_id_mismatch")
        }
        if (VisualKeyboardPolicy.refusal(frame, node, "keyboard") != null) {
            return Result(failureStage = "visual_keyboard_policy_refused")
        }
        val boundFrame = if (frame.visual == proof) frame else frame.copy(visual = proof)
        val selected = VisualSequenceCandidates.select(boundFrame, listOf(expectedLabel))
        if (selected.singleOrNull()?.id != node.id) {
            return Result(failureStage = "native_candidate_not_unique")
        }
        val imeBounds = KeyboardImageFallbackPolicy.bounds(proof.imeBounds)
            ?: return Result(failureStage = "ime_bounds_invalid")
        val nodeBounds = KeyboardImageFallbackPolicy.bounds(node.bounds)
            ?: return Result(failureStage = "node_bounds_invalid")
        if (!imeBounds.contains(nodeBounds) || !proof.window.contains(nodeBounds)) {
            return Result(failureStage = "node_outside_ime_or_viewport")
        }
        val nodeW = nodeBounds.right - nodeBounds.left
        val nodeH = nodeBounds.bottom - nodeBounds.top
        if (node.parent < 0 || !(node.textEntryKey || node.visualKeyChildCount == 1) ||
            nodeW < 16 || nodeH < 20 || nodeW > nodeH * 2) {
            return Result(failureStage = "native_key_shape_unverified")
        }

        val rows = verifiedPhysicalRows(boundFrame, ime, proof, imeBounds)
        val nodeInRow = rows.any { row ->
            row.parent == node.parent &&
                abs(nodeBounds.top - row.top) <= 2 &&
                abs(nodeBounds.bottom - row.bottom) <= 2 &&
                abs(nodeW - row.width) <= 2 &&
                (row.keys.any { it.node.id == node.id } ||
                    (row.keys.all { it.rect.right <= nodeBounds.left || nodeBounds.right <= it.rect.left } &&
                        row.keys.any {
                            nodeBounds.left - it.rect.right in 0..row.width ||
                                it.rect.left - nodeBounds.right in 0..row.width
                        }))
        }
        if (!nodeInRow) {
            return Result(failureStage = "native_physical_row_unverified")
        }

        val hasTwoVerticalRows = rows.any { a -> rows.any { b -> a.bottom <= b.top } }
        if (!hasTwoVerticalRows) {
            return Result(failureStage = "native_multirow_layout_unverified")
        }

        val targetScript = Character.UnicodeScript.of(expectedLabel.codePointAt(0))
        val rowCells = rows.flatMap { it.keys }
        val sameScriptLabels = rowCells
            .filter { Character.UnicodeScript.of(it.label.codePointAt(0)) == targetScript }
            .map { it.label }
            .distinct()
        val conflictingScriptPresent = targetScript in alphabeticScripts && rowCells.any { cell ->
            val script = Character.UnicodeScript.of(cell.label.codePointAt(0))
            script in alphabeticScripts && script != targetScript
        }
        if (sameScriptLabels.size < 6 || conflictingScriptPresent) {
            return Result(failureStage = "native_layout_script_unverified")
        }

        val distinctNative = requiredNativeLabels.distinct()
        if (distinctNative.size >= 2 && !VisualSequenceCandidates.witnessesLayout(boundFrame, distinctNative)) {
            return Result(failureStage = "native_layout_witness_unverified")
        }
        val layoutFingerprint = KeyboardTouchLayout.fingerprint(
            VisualSequenceCandidates.layoutNodes(boundFrame), imeBounds, ime,
        )
        if (layoutFingerprint.isEmpty()) {
            return Result(failureStage = "native_layout_fingerprint_empty")
        }

        val rawCrop = KeyboardImageFallbackPolicy.crop(proof, nodeBounds)
            ?: return Result(failureStage = "crop_unverified_or_masked")
        val resolved = KeyboardSequencePixelProof.resolvedCrop(
            proof, rawCrop, nodeBounds, imeBounds, requireWholeNode = false,
        ) ?: return Result(failureStage = "resolved_crop_unverified")
        val mapped = proof.map(resolved)
            ?: return Result(failureStage = "mapped_crop_not_disclosed")
        if (!nodeBounds.contains(mapped) || !OwnedPixelPolicy.contains(proof.disclosedRegions, mapped)) {
            return Result(failureStage = "mapped_crop_outside_owned_node")
        }

        val ownedKeys = boundFrame.nodes.filter {
            it.id != node.id && it.enabled && !it.password &&
                it.sourceWindowId == ime.windowId && it.sourcePackage == ime.packageName &&
                (it.keyboardKey.isNotEmpty() || it.keyboardImageCandidate)
        }
        val overlappingNeighbor = ownedKeys.any { other ->
            if (KeyboardImageFallbackPolicy.isRowBackdrop(boundFrame, other)) return@any false
            val sameRoleWrapper = VisualKeyboardPolicy.identifyCandidate(other, ime) == role &&
                (other.bounds == node.bounds || other.parent == node.id || node.parent == other.id)
            if (sameRoleWrapper) return@any false
            val otherRect = KeyboardImageFallbackPolicy.bounds(other.bounds) ?: return@any false
            otherRect.left < mapped.right && mapped.left < otherRect.right &&
                otherRect.top < mapped.bottom && mapped.top < otherRect.bottom
        }
        if (overlappingNeighbor) {
            return Result(failureStage = "neighbor_key_overlaps_interior")
        }
        return Result(crop = resolved, failureStage = null)
    }

    private fun verifiedPhysicalRows(
        frame: ControlFrame,
        ime: ControlKeyboard,
        proof: VisualTargetProof,
        imeBounds: TapBounds,
    ): List<Row> {
        val cells = frame.nodes.mapNotNull { peer ->
            if (!peer.enabled || peer.password || peer.parent < 0 ||
                peer.sourceWindowId != ime.windowId || peer.sourcePackage != ime.packageName ||
                !peer.visualKeyCandidate || peer.keyboardImageCandidate ||
                !(peer.textEntryKey || peer.visualKeyChildCount == 1)) return@mapNotNull null
            val role = VisualKeyboardPolicy.identifyCandidate(peer, ime)
                ?.takeIf { it.kind == KeyboardKeyPolicy.Kind.CHARACTER } ?: return@mapNotNull null
            val rect = KeyboardImageFallbackPolicy.bounds(peer.bounds) ?: return@mapNotNull null
            val w = rect.right - rect.left
            val h = rect.bottom - rect.top
            if (w < 16 || h < 20 || w > h * 2) return@mapNotNull null
            if (!imeBounds.contains(rect) || !proof.window.contains(rect) ||
                !OwnedPixelPolicy.contains(proof.disclosedRegions, rect)) return@mapNotNull null
            Cell(peer, rect, role.label)
        }

        val rows = mutableListOf<Row>()
        for ((parentId, parentCells) in cells.groupBy { it.node.parent }) {
            val sorted = parentCells.sortedWith(compareBy<Cell>({ it.rect.top }, { it.rect.left }))
            val clusters = mutableListOf<MutableList<Cell>>()
            for (cell in sorted) {
                val cellW = cell.rect.right - cell.rect.left
                val match = clusters.firstOrNull { cluster ->
                    val anchor = cluster.first()
                    val anchorW = anchor.rect.right - anchor.rect.left
                    abs(cell.rect.top - anchor.rect.top) <= 2 &&
                        abs(cell.rect.bottom - anchor.rect.bottom) <= 2 &&
                        abs(cellW - anchorW) <= 2
                }
                if (match != null) match.add(cell) else clusters.add(mutableListOf(cell))
            }
            for (cluster in clusters) {
                val ordered = cluster.sortedBy { it.rect.left }
                if (ordered.size < 3 || ordered.zipWithNext().any { (a, b) -> a.rect.right > b.rect.left }) {
                    continue
                }
                val first = ordered.first()
                rows += Row(
                    parent = parentId,
                    top = first.rect.top,
                    bottom = first.rect.bottom,
                    width = first.rect.right - first.rect.left,
                    keys = ordered,
                )
            }
        }
        return rows
    }
}
