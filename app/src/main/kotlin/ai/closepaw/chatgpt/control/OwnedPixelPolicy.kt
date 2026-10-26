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
    /** Disjoint one-row runs of independently proven pixels. Unknown compositor
     * pixels are never disclosed. Bound fragmentation rather than approximating. */
    fun verifiedRuns(display: IntArray,displayWidth: Int,isolated: IntArray,window: TapBounds,
        rows: List<TapBounds>, limit: Int=4096): List<TapBounds>? {
        val w=window.right-window.left;val h=window.bottom-window.top
        if(!window.valid || displayWidth<=0 || display.size%displayWidth!=0 || isolated.size.toLong()!=w.toLong()*h) return null
        val all=TapBounds(0,0,displayWidth,display.size/displayWidth);val runs=mutableListOf<TapBounds>()
        for(rect in rows) {
            if(!window.contains(rect)||!all.contains(rect)) return null
            for(y in rect.top until rect.bottom) {
                var start=-1
                for(x in rect.left..rect.right) {
                    val equal=x<rect.right && display[y*displayWidth+x]==isolated[(y-window.top)*w+x-window.left]
                    if(equal && start<0) start=x
                    if(!equal && start>=0) {
                        if(runs.size>=limit) return null
                        runs.add(TapBounds(start,y,x,y+1));start=-1
                    }
                }
            }
        }
        return runs
    }
    /** Native proof regions are disjoint (RegionIterator output). */
    fun contains(regions: List<TapBounds>, target: TapBounds): Boolean {
        if(!target.valid || regions.isEmpty()) return false
        val area=(target.right-target.left).toLong()*(target.bottom-target.top)
        var covered=0L
        for(r in regions) {
            val width=(minOf(r.right,target.right)-maxOf(r.left,target.left)).coerceAtLeast(0)
            val height=(minOf(r.bottom,target.bottom)-maxOf(r.top,target.top)).coerceAtLeast(0)
            covered+=width.toLong()*height
        }
        return covered==area
    }

}
