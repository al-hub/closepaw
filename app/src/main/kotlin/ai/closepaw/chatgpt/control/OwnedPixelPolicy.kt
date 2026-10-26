package ai.closepaw.chatgpt.control

/** The compositor's interactive-window list is not complete surface ownership.
 * A display pixel may be disclosed only if it exactly equals an independently
 * window-isolated pixel at the same verified coordinate. Endpoint equality or
 * events alone never certify pixels belonging to an unreported overlay. */
internal object OwnedPixelPolicy {
    fun matches(display: IntArray, displayWidth: Int, isolated: IntArray, window: TapBounds,
        rows: List<TapBounds>): Boolean {
        val w=window.right-window.left; val h=window.bottom-window.top
        if (!window.valid || displayWidth<=0 || display.size%displayWidth!=0 ||
            isolated.size.toLong()!=w.toLong()*h) return false
        val displayBounds=TapBounds(0,0,displayWidth,display.size/displayWidth)
        for(rect in rows) {
            if (!window.contains(rect) || !displayBounds.contains(rect)) return false
            for(y in rect.top until rect.bottom) for(x in rect.left until rect.right) {
                if(display[y*displayWidth+x]!=isolated[(y-window.top)*w+x-window.left]) return false
            }
        }
        return rows.isNotEmpty()
    }
}
