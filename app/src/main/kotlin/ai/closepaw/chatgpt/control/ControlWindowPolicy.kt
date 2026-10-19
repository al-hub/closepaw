package ai.closepaw.chatgpt.control

internal data class ControlWindow(val id: Int, val display: Int, val layer: Int, val application: Boolean,
    val active: Boolean, val focused: Boolean, val packageName: String, val hasRoot: Boolean = true,
    val parent: Int? = null, val anchor: Int? = null, val pictureInPicture: Boolean = false)
internal data class ControlWindowSelection(val foreground: Int, val target: Int, val members: List<Int>, val reason: String? = null)

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
