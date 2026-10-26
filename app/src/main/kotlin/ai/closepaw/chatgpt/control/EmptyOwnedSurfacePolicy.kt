package ai.closepaw.chatgpt.control

/** A completely scanned, OS-linked empty popup is not permission to click through.
 * Preserve its identity only for the existing guarded Home/exact-launch path.
 * Native callers must first verify the complete window set, all roots, security,
 * package access and freshness. No other denial can be converted to admission. */
internal object EmptyOwnedSurfacePolicy {
    fun restrict(frame: ControlFrame, linkedPopup: Boolean): ControlFrame =
        if (linkedPopup && frame.windowId != frame.foregroundWindowId &&
            frame.reason == "screen_unreadable" && !frame.truncated && frame.nodes.isEmpty())
            frame.copy(reason = null, navigationOnly = true)
        else frame
}
