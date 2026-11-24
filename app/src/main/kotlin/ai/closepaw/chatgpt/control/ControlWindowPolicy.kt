package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.operations.operationHash

internal data class ControlWindow(val id: Int, val display: Int, val layer: Int, val application: Boolean,
    val active: Boolean, val focused: Boolean, val packageName: String, val hasRoot: Boolean = true,
    val parent: Int? = null, val anchor: Int? = null, val pictureInPicture: Boolean = false)
internal data class ControlWindowSelection(val foreground: Int, val target: Int, val members: List<Int>, val reason: String? = null)

/** Internal-only screen rect snapshot from AccessibilityWindowInfo.getBoundsInScreen; never serialized to JSON. */
internal data class ControlWindowRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isNonEmpty: Boolean get() = right > left && bottom > top
    fun overlaps(other: ControlWindowRect): Boolean =
        isNonEmpty && other.isNonEmpty &&
            left < other.right && right > other.left &&
            top < other.bottom && bottom > other.top
    fun flatten(): String = "$left $top $right $bottom"
}

/**
 * Internal-only record capturing every native window input hashed into NativeControlWindows.signature
 * (projected ControlWindow + windowType + screen bounds). Never serialized into public JSON diagnostics.
 */
internal data class ControlWindowRecord(
    val window: ControlWindow,
    val windowType: Int,
    val bounds: ControlWindowRect,
) {
    companion object {
        const val TYPE_APPLICATION = 1
        const val TYPE_INPUT_METHOD = 2
        const val TYPE_SYSTEM = 3
        const val TYPE_ACCESSIBILITY_OVERLAY = 4
    }
}

/**
 * Internal-only continuity snapshot built from the exact same native AccessibilityWindowInfo list
 * that computes NativeControlWindows.signature, ensuring cryptographic binding with zero omitted inputs.
 * Never serialized into public JSON diagnostics.
 */
internal data class ControlWindowSnapshot(
    val foregroundWindowId: Int,
    val selectedWindowId: Int,
    val totalWindowCount: Int,
    val records: List<ControlWindowRecord>,
) {
    val truncated: Boolean get() = totalWindowCount > records.size || totalWindowCount > 64

    fun signatureInput(): String {
        val sorted = records.sortedBy { it.window.id }
        val projected = sorted.joinToString("\n") { it.window.toString() }
        val geometry = sorted.joinToString("\n") { "${it.window.id}:${it.windowType}:${it.bounds.flatten()}" }
        return "$projected\n$geometry"
    }

    fun baseSignature(): String = operationHash(signatureInput())

    fun windowContext(keyboard: ControlKeyboard?): String =
        if (keyboard == null) baseSignature()
        else operationHash(baseSignature() + "\n" + keyboard.toString())
}

/**
 * Internal-only non-IME continuity evaluator operating directly on ControlWindowSnapshot.
 * Verifies cryptographic binding to ControlFrame.windowContext so no omitted signature input
 * (PiP, window type, packageName, or window bounds) can silently drift across a guarded transition.
 */
internal object ControlWindowContinuity {
    private val TRUSTED_SYSTEM_PACKAGES = setOf("com.android.systemui", "android")

    /**
     * Conservative owner/overlap gate for non-application windows (excluding the target Chrome window
     * and the removed IME). ClosePaw's own remote-control indicator overlay is already stripped before
     * controlWindows(), so any remaining TYPE_ACCESSIBILITY_OVERLAY or non-TYPE_SYSTEM overlay is refused.
     * For TYPE_SYSTEM windows: trusted system owners (com.android.systemui / android with live root)
     * are allowed; non-system owners and live-root windows with blank packageName are refused;
     * root-less TYPE_SYSTEM windows are refused whenever they overlap the target Chrome window
     * or have empty/invalid bounds.
     */
    private fun nonAppWindowOwnerRefusal(
        rec: ControlWindowRecord,
        vararg targetBounds: ControlWindowRect,
    ): String? {
        if (rec.windowType != ControlWindowRecord.TYPE_SYSTEM) return "unverified_overlay_present"
        if (rec.window.active || rec.window.focused) return "untrusted_non_app_window_owner"
        val owner = rec.window.packageName
        if (rec.window.hasRoot && owner in TRUSTED_SYSTEM_PACKAGES) return null
        if (owner.isNotEmpty()) return "untrusted_non_app_window_owner"
        if (rec.window.hasRoot) return "unverified_non_app_window_owner"
        if (!rec.bounds.isNonEmpty || targetBounds.any { rec.bounds.overlaps(it) })
            return "unverified_overlapping_system_window"
        return null
    }

    private fun validateSnapshot(
        snapshot: ControlWindowSnapshot?,
        expectedWindowId: Int,
        expectedWindowContext: String,
        keyboard: ControlKeyboard?,
    ): Pair<ControlWindowSnapshot?, String?> {
        if (snapshot == null) return null to "window_snapshot_missing"
        if (snapshot.truncated) return null to "window_snapshot_truncated"
        if (snapshot.totalWindowCount < 1 || snapshot.records.size != snapshot.totalWindowCount)
            return null to "window_snapshot_invalid"
        if (snapshot.records.map { it.window.id }.distinct().size != snapshot.records.size)
            return null to "window_snapshot_invalid"
        if (snapshot.foregroundWindowId != expectedWindowId || snapshot.selectedWindowId != expectedWindowId)
            return null to "foreground_window_changed"
        if (snapshot.windowContext(keyboard) != expectedWindowContext)
            return null to "window_snapshot_signature_mismatch"
        return snapshot to null
    }

    /**
     * Allows target-window bottom-edge restoration ONLY when it is geometrically contained
     * within the removed full-width IME's vertical span (AOSP touchable-region subtraction / adjustResize).
     * Rejects any horizontal/top shift, shrink, split-screen, freeform, or expansion outside the IME span.
     */
    internal fun isProvableImeOcclusionRestoration(
        beforeTarget: ControlWindowRect,
        afterTarget: ControlWindowRect,
        removedIme: ControlWindowRect,
    ): Boolean {
        if (!beforeTarget.isNonEmpty || !afterTarget.isNonEmpty || !removedIme.isNonEmpty) return false
        if (afterTarget.left != beforeTarget.left ||
            afterTarget.top != beforeTarget.top ||
            afterTarget.right != beforeTarget.right) return false
        if (afterTarget.bottom <= beforeTarget.bottom) return false
        if (removedIme.top < beforeTarget.top) return false
        if (removedIme.left > beforeTarget.left || removedIme.right < beforeTarget.right) return false
        return beforeTarget.bottom >= removedIme.top && afterTarget.bottom <= removedIme.bottom
    }

    fun exactSnapshotRefusal(
        before: ControlFrame,
        after: ControlFrame,
        expectedPackage: String,
    ): String? {
        val (bSnap, bErr) = validateSnapshot(before.windowSnapshot, before.windowId, before.windowContext, before.keyboard)
        if (bErr != null || bSnap == null) return bErr
        val (aSnap, aErr) = validateSnapshot(after.windowSnapshot, after.windowId, after.windowContext, after.keyboard)
        if (aErr != null || aSnap == null) return aErr
        val target = aSnap.records.singleOrNull { it.window.id == after.windowId } ?: return "target_window_missing"
        if (target.windowType != ControlWindowRecord.TYPE_APPLICATION) return "window_type_changed"
        if (target.window.pictureInPicture) return "chrome_pip_detected"
        if (target.window.packageName != expectedPackage) return "window_package_changed"
        if (bSnap != aSnap) return "window_snapshot_changed"
        val allowedImeId = after.keyboard?.windowId
        for (rec in aSnap.records) {
            if (rec.window.id == after.windowId || rec.window.id == allowedImeId) continue
            if (!rec.window.application) {
                nonAppWindowOwnerRefusal(rec, target.bounds)?.let { return it }
            }
        }
        return null
    }

    fun imeDismissalRefusal(
        before: ControlFrame,
        after: ControlFrame,
        removedImeWindowId: Int,
        expectedPackage: String,
    ): String? {
        val (bSnap, bErr) = validateSnapshot(before.windowSnapshot, before.windowId, before.windowContext, before.keyboard)
        if (bErr != null || bSnap == null) return bErr
        val (aSnap, aErr) = validateSnapshot(after.windowSnapshot, after.windowId, after.windowContext, after.keyboard)
        if (aErr != null || aSnap == null) return aErr

        val bById = bSnap.records.associateBy { it.window.id }
        val aById = aSnap.records.associateBy { it.window.id }
        if ((aById.keys - bById.keys).isNotEmpty()) return "unexpected_window_added"
        val imeRec = bById[removedImeWindowId] ?: return "ime_window_not_in_baseline"
        if (imeRec.windowType != ControlWindowRecord.TYPE_INPUT_METHOD ||
            imeRec.window.application || imeRec.window.pictureInPicture) return "ime_window_invalid"
        if (removedImeWindowId in aById) return "ime_window_still_present"
        if ((bById.keys - aById.keys) != setOf(removedImeWindowId)) return "non_ime_window_set_changed"

        val bTarget = bById[before.windowId] ?: return "target_window_missing"
        val aTarget = aById[after.windowId] ?: return "target_window_missing"
        if (bTarget.windowType != ControlWindowRecord.TYPE_APPLICATION ||
            aTarget.windowType != ControlWindowRecord.TYPE_APPLICATION) return "window_type_changed"
        if (bTarget.window.pictureInPicture || aTarget.window.pictureInPicture) return "chrome_pip_detected"
        if (bTarget.window.packageName != expectedPackage ||
            aTarget.window.packageName != expectedPackage) return "window_package_changed"
        if (!bTarget.window.application || !aTarget.window.application ||
            !bTarget.window.active || !aTarget.window.active ||
            !bTarget.window.focused || !aTarget.window.focused ||
            !bTarget.window.hasRoot || !aTarget.window.hasRoot) return "target_window_state_changed"
        if (bTarget.window.layer != aTarget.window.layer) return "non_ime_window_layer_changed"
        if (bTarget.window != aTarget.window) return "non_ime_window_changed"
        if (aTarget.bounds != bTarget.bounds &&
            !isProvableImeOcclusionRestoration(bTarget.bounds, aTarget.bounds, imeRec.bounds))
            return "chrome_window_bounds_changed"

        val bRemaining = bSnap.records.filterNot { it.window.id == removedImeWindowId }
        val aRemaining = aSnap.records
        if (bRemaining.size != aRemaining.size) return "non_ime_window_set_changed"
        if (bRemaining.sortedWith(compareBy({ it.window.layer }, { it.window.id })).map { it.window.id } !=
            aRemaining.sortedWith(compareBy({ it.window.layer }, { it.window.id })).map { it.window.id })
            return "non_ime_window_layer_changed"

        val aboveImeShifts = mutableListOf<Int>()
        for (bRec in bRemaining) {
            if (bRec.window.id == before.windowId) continue
            val aRec = aById.getValue(bRec.window.id)
            if (bRec.windowType != aRec.windowType) return "window_type_changed"
            if (bRec.window.pictureInPicture != aRec.window.pictureInPicture) return "non_ime_window_pip_changed"
            if (bRec.window.packageName != aRec.window.packageName) return "window_package_changed"
            if (bRec.bounds != aRec.bounds) return "non_ime_window_bounds_changed"
            if (bRec.window.copy(layer = 0) != aRec.window.copy(layer = 0)) return "non_ime_window_changed"
            if (!bRec.window.application) {
                nonAppWindowOwnerRefusal(bRec, bTarget.bounds, aTarget.bounds)?.let { return it }
                nonAppWindowOwnerRefusal(aRec, bTarget.bounds, aTarget.bounds)?.let { return it }
            }
            val delta = aRec.window.layer - bRec.window.layer
            if (bRec.window.application || bRec.window.layer <= imeRec.window.layer) {
                if (delta != 0) return "non_ime_window_layer_changed"
            } else {
                if (delta != 0 && delta != -1) return "non_ime_window_layer_changed"
                aboveImeShifts += delta
            }
        }
        if (aboveImeShifts.distinct().size > 1) return "non_ime_window_layer_changed"
        return null
    }
}

/** Foreground ownership is proven by OS window relationships, never package co-occurrence. */
internal object ControlWindowPolicy {
    fun select(foreground: Int, pkg: String, windows: List<ControlWindow>): ControlWindowSelection {
        fun refuse(reason: String) = ControlWindowSelection(foreground, foreground, emptyList(), reason)
        if (windows.size > 64 || windows.map { it.id }.distinct().size != windows.size) return refuse("window_list_ambiguous")
        val base = windows.singleOrNull { it.id == foreground }
        if (base == null || !base.application || !base.active || !base.focused || !base.hasRoot ||
            base.packageName != pkg || base.pictureInPicture)
            return refuse("foreground_application_required")
        val byId = windows.associateBy { it.id }
        val owned = linkedSetOf(foreground)
        val queue = ArrayDeque<ControlWindow>().apply { add(base) }
        // A focused popup may itself be rootInActiveWindow. Include its OS-linked host too.
        while (queue.isNotEmpty()) {
            val child = queue.removeFirst()
            for (id in listOfNotNull(child.parent, child.anchor).distinct()) {
                val parent = byId[id] ?: return refuse("window_owner_missing")
                if (!parent.application || parent.display != base.display || parent.layer >= child.layer ||
                    parent.packageName != pkg || !parent.hasRoot || parent.pictureInPicture)
                    return refuse("window_owner_unverified")
                if (owned.add(id)) queue.add(parent)
                if (owned.size > 4) return refuse("owned_window_limit")
            }
        }
        val lowest = owned.minOf { byId.getValue(it).layer }
        val applications = windows.filter { it.application && it.display == base.display && it.layer >= lowest }.sortedBy { it.layer }
        if (applications.map { it.layer }.distinct().size != applications.size) return refuse("ambiguous_application_windows")
        for (candidate in applications) {
            if (candidate.id in owned) continue
            if (candidate.packageName != pkg || !candidate.hasRoot || candidate.pictureInPicture)
                return refuse("covering_application_unverified")
            val owners = listOfNotNull(candidate.parent, candidate.anchor).distinct()
            if (owners.isEmpty() || owners.any { it !in owned || byId.getValue(it).layer >= candidate.layer })
                return refuse("unlinked_application_window")
            owned.add(candidate.id)
            if (owned.size > 4) return refuse("owned_window_limit")
        }
        val members = owned.sortedBy { byId.getValue(it).layer }
        return ControlWindowSelection(foreground, members.last(), members)
    }
}
