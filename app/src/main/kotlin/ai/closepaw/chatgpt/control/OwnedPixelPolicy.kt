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
    /** Exact independently verified pixels, vertically coalesced into rectangles.
     * No mismatched pixel can enter a rectangle; disconnected rows never merge.
     * Fragmentation is bounded by the number of resulting rectangles, not by
     * image height. The caller still masks the complement of these regions. */
    fun verifiedRuns(display: IntArray,displayWidth: Int,isolated: IntArray,window: TapBounds,
        rows: List<TapBounds>, limit: Int=4096): List<TapBounds>? {
        val w=window.right-window.left;val h=window.bottom-window.top
        if(!window.valid || displayWidth<=0 || display.size%displayWidth!=0 ||
            isolated.size.toLong()!=w.toLong()*h || limit<=0 || rows.isEmpty()) return null
        val all=TapBounds(0,0,displayWidth,display.size/displayWidth)
        val result=mutableListOf<TapBounds>()
        // Each candidate rect is certified against isolated pixels. RegionIterator
        // supplies disjoint rectangles, so merging only within a rect is safe.
        for(rect in rows) {
            if(!window.contains(rect)||!all.contains(rect)) return null
            var active=mutableMapOf<Pair<Int,Int>,TapBounds>()
            fun flush(value: TapBounds): Boolean {
                if(result.size>=limit) return false
                result.add(value);return true
            }
            for(y in rect.top until rect.bottom) {
                val current=mutableMapOf<Pair<Int,Int>,TapBounds>()
                var start=-1
                for(x in rect.left..rect.right) {
                    val equal=x<rect.right &&
                        display[y*displayWidth+x]==isolated[(y-window.top)*w+x-window.left]
                    if(equal && start<0) start=x
                    if(!equal && start>=0) {
                        val key=start to x
                        val previous=active.remove(key)
                        current[key]=if(previous!=null)
                            TapBounds(previous.left,previous.top,previous.right,y+1)
                        else TapBounds(start,y,x,y+1)
                        start=-1
                    }
                }
                for(previous in active.values) if(!flush(previous)) return null
                active=current
            }
            for(previous in active.values) if(!flush(previous)) return null
        }
        return result
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
