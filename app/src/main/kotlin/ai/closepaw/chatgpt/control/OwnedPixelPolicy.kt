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
    /** Preserve a conservative subset when exact run segmentation exceeds the
     * budget. Every pixel of each tile must independently match; mismatches and
     * region gaps are discarded, never filled. This trades coverage for bounded
     * metadata, without weakening ownership or gesture-path admission. */
    fun verifiedRegions(display: IntArray, displayWidth: Int, isolated: IntArray, window: TapBounds,
        rows: List<TapBounds>, limit: Int = 4096): List<TapBounds>? {
        val width = window.right - window.left
        val height = window.bottom - window.top
        if (!window.valid || displayWidth <= 0 || display.size % displayWidth != 0 ||
            isolated.size.toLong() != width.toLong() * height || limit <= 0 || rows.isEmpty()) return null
        val all = TapBounds(0, 0, displayWidth, display.size / displayWidth)
        if (rows.any { !window.contains(it) || !all.contains(it) }) return null
        // The input is disjoint RegionIterator output. Reject overlapping input
        // rather than allowing duplicate area to authorize an uncovered target.
        for (i in rows.indices) for (j in 0 until i) {
            val a = rows[i]; val b = rows[j]
            if (maxOf(a.left, b.left) < minOf(a.right, b.right) &&
                maxOf(a.top, b.top) < minOf(a.bottom, b.bottom)) return null
        }
        verifiedRuns(display, displayWidth, isolated, window, rows, limit)?.let { return it }
        var tile = 32L
        fun count(): Long = rows.sumOf {
            ((it.right.toLong() - it.left + tile - 1) / tile) *
                ((it.bottom.toLong() - it.top + tile - 1) / tile)
        }
        // At least one tile per input region is required. Invalid/over-budget
        // geometry remains a refusal rather than dropping an arbitrary window.
        if (rows.size > limit) return null
        while (count() > limit) tile *= 2
        val result = mutableListOf<TapBounds>()
        for (row in rows) {
            var top = row.top
            while (top < row.bottom) {
                val bottom = minOf(row.bottom.toLong(), top.toLong() + tile).toInt()
                var left = row.left
                while (left < row.right) {
                    val right = minOf(row.right.toLong(), left.toLong() + tile).toInt()
                    val candidate = TapBounds(left, top, right, bottom)
                    if (matches(display, displayWidth, isolated, window, listOf(candidate))) result.add(candidate)
                    left = right
                }
                top = bottom
            }
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
