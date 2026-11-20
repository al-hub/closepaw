package ai.closepaw.chatgpt.control

/** Private geometry hints, never a source proof or dispatch authority. A hit
 * still needs the live IME candidate, fresh ownership and native pixel guards. */
internal class VisualKeyMapCache {
    data class Owner(val session: String, val epoch: Long, val consent: Long, val configuration: Long,
        val windowContext: String, val ime: ControlKeyboard, val editor: String, val editorClass: String)
    data class Layout(val fingerprint: String, val window: TapBounds, val imeBounds: String,
        val rotation: Int, val width: Int, val height: Int)
    data class Entry(val screenBounds: TapBounds, val crop: TapBounds, val imageOnly: Boolean)

    private var owner: Owner? = null
    private val maps = LinkedHashMap<Layout, MutableMap<String, Entry>>(8, .75f, true)

    fun clear() { owner = null; maps.clear() }

    private fun bind(next: Owner) {
        if (owner != next) { clear(); owner = next }
    }

    fun find(next: Owner, layout: Layout, label: String): Entry? {
        bind(next)
        return maps[layout]?.get(label)
    }

    fun remember(next: Owner, layout: Layout, label: String, entry: Entry): Boolean {
        bind(next)
        val newLayout = layout !in maps
        val map = maps.getOrPut(layout) { linkedMapOf() }
        map[label] = entry
        if (map.size > 64) map.remove(map.keys.first())
        if (maps.size > 8) maps.remove(maps.keys.first())
        return newLayout
    }
}
