package ai.closepaw.chatgpt.control

/** Restricted initial image fallback for one unlabeled key in a verified
 * literal row. More IMEs and key roles need their own native structure proof. */
internal object KeyboardImageFallbackPolicy {
    fun trustedIme(keyboard: ControlKeyboard?, sourcePackage: String): Boolean =
        keyboard?.packageName == "com.samsung.android.honeyboard" &&
            keyboard.inputMethodId == "com.samsung.android.honeyboard/.service.HoneyBoardService" &&
            keyboard.packageName == sourcePackage

    fun nodeShape(text: String, description: String, className: String, clickable: Boolean,
        textEntryKey: Boolean, childCount: Int): Boolean =
        text.isEmpty() && description.isEmpty() && clickable && textEntryKey && childCount in 0..1 &&
            className in setOf("android.widget.Button", "android.view.ViewGroup")

    fun key(label: String?): KeyboardKeyPolicy.Key? {
        val ch = label?.singleOrNull() ?: return null
        return if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9')
            KeyboardKeyPolicy.Key(ch.toString(), KeyboardKeyPolicy.Kind.CHARACTER) else null
    }

    fun bounds(raw: String): TapBounds? {
        val parts = raw.split(' ').map { it.toIntOrNull() ?: return null }
        return if (parts.size == 4) TapBounds(parts[0], parts[1], parts[2], parts[3]).takeIf { it.valid } else null
    }

    private fun candidate(frame: ControlFrame, node: ControlNode): Boolean {
        val ime = frame.keyboard ?: return false
        if (!node.keyboardImageCandidate || !node.enabled || node.password ||
            node.sourceWindowId != ime.windowId || !trustedIme(ime, node.sourcePackage) ||
            node.parent < 0 || node.keyboardKey.isNotEmpty() ||
            !nodeShape(node.text, node.description, node.className, node.clickable, node.textEntryKey, node.visualKeyChildCount)) return false
        val target = bounds(node.bounds) ?: return false
        val w = target.right - target.left
        val h = target.bottom - target.top
        val (x, y) = target.center
        fun sameOwner(other: ControlNode) = other.sourceWindowId == ime.windowId && other.sourcePackage == ime.packageName
        if (frame.nodes.any { it.id != node.id && sameOwner(it) &&
                KeyboardKeyPolicy.identifyLabel(it.text, it.description) != null &&
                bounds(it.bounds)?.contains(x, y) == true }) return false
        val anchors = frame.nodes.mapNotNull { sibling ->
            if (sibling.id == node.id || sibling.parent != node.parent || !sameOwner(sibling) ||
                !sibling.enabled || sibling.password || !sibling.textEntryKey ||
                key(sibling.keyboardKey) == null) return@mapNotNull null
            val native = KeyboardKeyPolicy.identify(sibling.text, sibling.description, sibling.className,
                sibling.clickable, sibling.textEntryKey) ?: return@mapNotNull null
            if (native.label != sibling.keyboardKey || native.kind != KeyboardKeyPolicy.Kind.CHARACTER) return@mapNotNull null
            val rect = bounds(sibling.bounds) ?: return@mapNotNull null
            val sw = rect.right - rect.left
            val sh = rect.bottom - rect.top
            rect.takeIf { sw in (w / 2)..(w * 2) && sh in (h * 3 / 4)..(h * 5 / 4) &&
                kotlin.math.abs(it.center.second - y) <= h / 8 }
        }.distinct()
        return anchors.count { target.left - it.right in 0..w } == 1 &&
            anchors.count { it.left - target.right in 0..w } == 1
    }

    fun refusal(frame: ControlFrame, node: ControlNode, label: String?): String? {
        val desired = key(label) ?: return "keyboard_image_label_unverified"
        val ime = frame.keyboard ?: return "keyboard_target_unverified"
        // Do not fall back when the requested key already has native evidence.
        if (frame.nodes.any { it.sourceWindowId == ime.windowId && it.sourcePackage == ime.packageName &&
                KeyboardKeyPolicy.identifyLabel(it.text, it.description) == desired })
            return "keyboard_image_use_native_label"
        if (!candidate(frame, node)) return "keyboard_image_row_unverified"
        if (frame.nodes.count { candidate(frame, it) } != 1) return "keyboard_image_candidate_ambiguous"
        return null
    }

    fun crop(proof: VisualTargetProof, screen: TapBounds): TapBounds? {
        if (proof.scope != "keyboard" || proof.sourceDisplayId != 0 ||
            !proof.window.contains(screen) || !OwnedPixelPolicy.contains(proof.disclosedRegions, screen)) return null
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

    fun matchesRead(expected: String, glyph: String, confidence: Float): Boolean =
        key(expected) != null && expected == glyph && confidence.isFinite() && confidence in 0.92f..1f
}
