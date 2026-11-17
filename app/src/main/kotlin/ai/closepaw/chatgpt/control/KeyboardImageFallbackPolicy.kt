package ai.closepaw.chatgpt.control

/** Native structure narrows an image proposal. It never proves pixels or dispatch. */
internal object KeyboardImageFallbackPolicy {
    fun trustedIme(keyboard: ControlKeyboard?, sourcePackage: String): Boolean =
        keyboard?.packageName == "com.samsung.android.honeyboard" &&
            keyboard.inputMethodId == "com.samsung.android.honeyboard/.service.HoneyBoardService" &&
            keyboard.packageName == sourcePackage

    private fun samsungLiteralWrapper(className: String, clickable: Boolean, textEntryKey: Boolean, childCount: Int): Boolean =
        className == "android.view.ViewGroup" && !clickable && !textEntryKey && childCount == 1

    fun nodeShape(text: String, description: String, className: String, clickable: Boolean,
        textEntryKey: Boolean, childCount: Int): Boolean =
        text.isEmpty() && description.isEmpty() && (samsungLiteralWrapper(className, clickable, textEntryKey, childCount) ||
            (clickable && textEntryKey && childCount in 0..1 &&
                className in setOf("android.widget.Button", "android.view.ViewGroup")))

    // These bounds are proposals only. A blank native leaf/wrapper does not
    // establish a key, much less the space role.
    fun spaceBoundary(text: String, description: String, className: String, childCount: Int): Boolean =
        text.isEmpty() && description.isEmpty() && childCount in 0..1 &&
            className in setOf("android.view.View", "android.view.ViewGroup", "android.widget.Button")

    fun key(label: String?): KeyboardKeyPolicy.Key? {
        if (label == null) return null
        return when (label) {
            "Space" -> KeyboardKeyPolicy.Key(label, KeyboardKeyPolicy.Kind.SPACE)
            "⌫" -> KeyboardKeyPolicy.Key(label, KeyboardKeyPolicy.Kind.BACKSPACE)
            "한/영" -> KeyboardKeyPolicy.Key(label, KeyboardKeyPolicy.Kind.LAYOUT)
            else -> {
                // Function glyphs, invisible format characters and arbitrary
                // strings are not character evidence. The key taxonomy also
                // excludes all known visual submission controls.
                if (label.codePointCount(0, label.length) != 1) return null
                KeyboardKeyPolicy.identifyLabel(label, "")?.takeIf { it.kind == KeyboardKeyPolicy.Kind.CHARACTER }
                    ?: label.codePointAt(0).takeIf { cp ->
                        cp > 127 && (Character.isLetter(cp) || Character.isDigit(cp))
                    }?.let { KeyboardKeyPolicy.Key(label, KeyboardKeyPolicy.Kind.CHARACTER) }
            }
        }
    }

    fun nativeShape(frame: ControlFrame, node: ControlNode): Boolean =
        frame.keyboard?.let { ime ->
            ime.inputMethodId.contains('/') && ime.inputMethodId.substringBefore('/') == ime.packageName &&
                node.keyboardImageCandidate && node.enabled && !node.password && node.parent >= 0 &&
                node.sourceWindowId == ime.windowId && node.sourcePackage == ime.packageName &&
                node.keyboardKey.isEmpty() && (spaceBoundary(node.text, node.description, node.className, node.visualKeyChildCount) ||
                    legacyShape(ime, node))
        } == true

    private fun legacyShape(ime: ControlKeyboard?, node: ControlNode): Boolean =
        nodeShape(node.text, node.description, node.className, node.clickable, node.textEntryKey, node.visualKeyChildCount) &&
            (node.clickable && node.textEntryKey || trustedIme(ime, node.sourcePackage))

    private data class OwnedKey(val rect: TapBounds, val key: KeyboardKeyPolicy.Key)

    private fun anchors(frame: ControlFrame, node: ControlNode): List<OwnedKey> {
        val ime = frame.keyboard ?: return emptyList()
        return frame.nodes.mapNotNull {
            if (!it.enabled || it.password || it.id == node.id ||
                it.sourcePackage != ime.packageName || it.sourceWindowId != ime.windowId) return@mapNotNull null
            val role = KeyboardKeyPolicy.identify(it.text, it.description, it.className, it.clickable, it.textEntryKey)
                ?: VisualKeyboardPolicy.identifyCandidate(it, ime)?.takeIf { _ -> it.visualKeyCandidate }
                ?: return@mapNotNull null
            val rect = bounds(it.bounds) ?: return@mapNotNull null
            OwnedKey(rect, role)
        }.distinct()
    }

    private fun overlaps(a: TapBounds, b: TapBounds): Boolean =
        maxOf(a.left, b.left) < minOf(a.right, b.right) && maxOf(a.top, b.top) < minOf(a.bottom, b.bottom)

    private fun structure(frame: ControlFrame, node: ControlNode, wanted: KeyboardKeyPolicy.Key?): Boolean {
        if (!nativeShape(frame, node)) return false
        val target = bounds(node.bounds) ?: return false
        val w = target.right - target.left
        val h = target.bottom - target.top
        val anchors = anchors(frame, node)
        if (anchors.any { overlaps(it.rect, target) }) return false
        val row = anchors.filter {
            kotlin.math.abs(it.rect.center.second - target.center.second) <= h / 6 &&
                it.rect.bottom - it.rect.top in (h * 3 / 4)..(h * 5 / 4)
        }
        val left = row.filter { target.left - it.rect.right in 0..(h / 2) }.maxByOrNull { it.rect.right }
        val right = row.filter { it.rect.left - target.right in 0..(h / 2) }.minByOrNull { it.rect.left }
        val above = anchors.filter { it.key.kind == KeyboardKeyPolicy.Kind.CHARACTER &&
            target.top - it.rect.bottom in 0..(h * 2) && it.rect.bottom - it.rect.top in (h * 3 / 4)..(h * 5 / 4) }
        val narrowKeysAbove = above.map { it.rect }.filter {
            it.center.first in target.left until target.right && (it.right - it.left).toLong() * 2 < w
        }
        val disjointAbove = narrowKeysAbove.isNotEmpty() &&
            narrowKeysAbove.maxOf { it.left } >= narrowKeysAbove.minOf { it.right }
        // Neighboring controls can themselves lack labels. Require verified
        // native boundaries on both sides, at least one known same-row role,
        // and two disjoint narrower alphabet/number keys in the row above.
        val ime = frame.keyboard ?: return false
        val sideBounds = row.map { it.rect } + frame.nodes.mapNotNull { other ->
            if (other.id == node.id || other.parent != node.parent || !nativeShape(frame, other)) return@mapNotNull null
            bounds(other.bounds)?.takeIf {
                kotlin.math.abs(it.center.second - target.center.second) <= h / 6 &&
                    it.bottom - it.top in (h * 3 / 4)..(h * 5 / 4) &&
                    (it.right - it.left).toLong() * 2 < w
            }
        }
        val wideBottomSlot = (left != null || right != null) && disjointAbove &&
            sideBounds.any { target.left - it.right in 0..h } &&
            sideBounds.any { it.left - target.right in 0..h } &&
            anchors.none { it.key.kind == KeyboardKeyPolicy.Kind.CHARACTER && it.rect.top >= target.bottom }
        val literalRow = legacyShape(ime, node) && node.clickable && node.textEntryKey && left != null && right != null &&
            left.key.kind == KeyboardKeyPolicy.Kind.CHARACTER && right.key.kind == KeyboardKeyPolicy.Kind.CHARACTER
        val edgeControl = legacyShape(ime, node) && node.clickable && node.textEntryKey &&
            (left?.key?.kind == KeyboardKeyPolicy.Kind.CHARACTER || right?.key?.kind == KeyboardKeyPolicy.Kind.CHARACTER)
        return when (wanted?.kind) {
            KeyboardKeyPolicy.Kind.SPACE -> wideBottomSlot
            KeyboardKeyPolicy.Kind.CHARACTER -> literalRow || slotLabel(frame, node) == wanted.label
            KeyboardKeyPolicy.Kind.BACKSPACE, KeyboardKeyPolicy.Kind.LAYOUT -> edgeControl
            KeyboardKeyPolicy.Kind.SUBMIT -> false
            null -> wideBottomSlot || literalRow || edgeControl || slotLabel(frame, node) != null
        }
    }

    fun canPropose(frame: ControlFrame, node: ControlNode): Boolean = structure(frame, node, null)

    fun bounds(raw: String): TapBounds? {
        val parts = raw.split(' ').map { it.toIntOrNull() ?: return null }
        return if (parts.size == 4) TapBounds(parts[0], parts[1], parts[2], parts[3]).takeIf { it.valid } else null
    }

    private val literalRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm", "1234567890")

    /** An unlabeled sibling is not a key merely because it has key-sized bounds.
     * Two same-row native labels must identify one interior slot in a supported
     * literal sequence. This is role evidence and cannot authorize any gesture. */
    fun slotLabel(frame: ControlFrame, node: ControlNode): String? {
        val ime = frame.keyboard ?: return null
        if (!nativeShape(frame, node) || !legacyShape(ime, node) || !trustedIme(ime, node.sourcePackage)) return null
        val target = bounds(node.bounds) ?: return null
        val w = target.right - target.left
        val h = target.bottom - target.top
        val (x, y) = target.center
        fun sameOwner(other: ControlNode) = other.sourceWindowId == ime.windowId && other.sourcePackage == ime.packageName
        if (frame.nodes.any { it.id != node.id && sameOwner(it) &&
                (KeyboardKeyPolicy.identifyLabel(it.text, it.description) != null ||
                    VisualKeyboardPolicy.identifyCandidate(it, ime) != null) &&
                bounds(it.bounds)?.contains(x, y) == true }) return null
        val wrapper = samsungLiteralWrapper(node.className, node.clickable, node.textEntryKey, node.visualKeyChildCount)
        val anchors = frame.nodes.mapNotNull { sibling ->
            if (sibling.id == node.id || sibling.parent != node.parent || !sameOwner(sibling) ||
                !sibling.enabled || sibling.password || key(sibling.keyboardKey) == null) return@mapNotNull null
            val native = if (wrapper) {
                if (!sibling.visualKeyCandidate || !samsungLiteralWrapper(sibling.className, sibling.clickable,
                        sibling.textEntryKey, sibling.visualKeyChildCount)) return@mapNotNull null
                VisualKeyboardPolicy.identifyCandidate(sibling, ime)
            } else KeyboardKeyPolicy.identify(sibling.text, sibling.description, sibling.className,
                sibling.clickable, sibling.textEntryKey)
            if (native == null || native.label != sibling.keyboardKey || native.kind != KeyboardKeyPolicy.Kind.CHARACTER) return@mapNotNull null
            val rect = bounds(sibling.bounds) ?: return@mapNotNull null
            val sw = rect.right - rect.left
            val sh = rect.bottom - rect.top
            rect.takeIf { sw in (w * 3 / 4)..(w * 5 / 4) && sh in (h * 3 / 4)..(h * 5 / 4) &&
                kotlin.math.abs(it.center.second - y) <= h / 8 }?.let { it to native.label }
        }
        val left = anchors.singleOrNull { target.left - it.first.right in 0..(w / 2) }?.second ?: return null
        val right = anchors.singleOrNull { it.first.left - target.right in 0..(w / 2) }?.second ?: return null
        return literalRows.flatMap { listOf(it, it.uppercase()) }.distinct().flatMap { row ->
            row.windowed(3).filter { it.first().toString() == left && it.last().toString() == right }.map { it[1].toString() }
        }.distinct().singleOrNull()
    }

    fun refusal(frame: ControlFrame, node: ControlNode, label: String?): String? {
        val desired = key(label) ?: return "keyboard_image_label_unverified"
        val ime = frame.keyboard ?: return "keyboard_target_unverified"
        // Do not fall back when the requested key already has native evidence.
        if (frame.nodes.any { it.sourceWindowId == ime.windowId && it.sourcePackage == ime.packageName &&
                it.enabled && !it.password && (it.visualKeyCandidate || it.textEntryKey) &&
                (VisualKeyboardPolicy.identifyCandidate(it, ime) ?: KeyboardKeyPolicy.identifyLabel(it.text, it.description))
                    ?.let { native -> native == desired || (native.kind == desired.kind &&
                        desired.kind in setOf(KeyboardKeyPolicy.Kind.SPACE, KeyboardKeyPolicy.Kind.BACKSPACE)) } == true })
            return "keyboard_image_use_native_label"
        if (!structure(frame, node, desired)) return "keyboard_image_row_unverified"
        if (frame.nodes.count { structure(frame, it, desired) } != 1) return "keyboard_image_candidate_ambiguous"
        return null
    }

    fun crop(proof: VisualTargetProof, screen: TapBounds): TapBounds? {
        if (proof.scope != "keyboard" || proof.sourceDisplayId != 0 ||
            !proof.window.contains(screen)) return null
        val w = proof.window.right - proof.window.left
        val h = proof.window.bottom - proof.window.top
        if (w <= 0 || h <= 0 || proof.image.width !in 1..960 || proof.image.height !in 1..960) return null
        fun x(v: Int) = ((v - proof.window.left).toLong() * proof.image.width / w).toInt()
        fun y(v: Int) = ((v - proof.window.top).toLong() * proof.image.height / h).toInt()
        val l = x(screen.left); val r = x(screen.right); val t = y(screen.top); val b = y(screen.bottom)
        val ix = (r - l) / 8; val iy = (b - t) / 8
        val inner = TapBounds(l + ix, t + iy, r - ix, b - iy)
        return inner.takeIf { ix >= 2 && iy >= 2 && proof.map(it)?.let { mapped -> screen.contains(mapped) } == true }
    }

    fun matchesRead(expected: String, glyph: String, confidence: Float): Boolean {
        val wanted = key(expected) ?: return false
        if (!confidence.isFinite() || confidence !in 0.92f..1f) return false
        // A language caption can also occur on a language switch. Only
        // structure's unique, wide bottom-row slot can use it as space.
        return if (wanted.kind == KeyboardKeyPolicy.Kind.SPACE)
            glyph in setOf("Space", "Spacebar", "스페이스", "공백", "한국어", "English (US)")
        else expected == glyph
    }
}
