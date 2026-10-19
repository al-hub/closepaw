package ai.closepaw.chatgpt.control

internal data class ControlWindow(val id: Int, val display: Int, val layer: Int, val application: Boolean,
    val active: Boolean, val focused: Boolean, val packageName: String, val hasRoot: Boolean = true,
    val parent: Int? = null, val anchor: Int? = null, val pictureInPicture: Boolean = false)
internal data class ControlWindowSelection(val foreground: Int, val target: Int, val members: List<Int>, val reason: String? = null)

/** Foreground ownership is proven by OS window relationships, never package co-occurrence. */
internal object ControlWindowPolicy {
    fun select(foreground: Int, pkg: String, windows: List<ControlWindow>): ControlWindowSelection {
        val base = windows.singleOrNull { it.id == foreground }
        if (base == null || !base.application || !base.active || !base.focused || !base.hasRoot ||
            base.packageName != pkg || base.pictureInPicture)
            return ControlWindowSelection(foreground, foreground, emptyList(), "foreground_application_required")
        return ControlWindowSelection(foreground, foreground, listOf(foreground))
    }
}
