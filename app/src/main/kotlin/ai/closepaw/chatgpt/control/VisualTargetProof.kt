package ai.closepaw.chatgpt.control

import ai.closepaw.model.ScreenImage
import java.util.UUID
import kotlinx.serialization.json.*

/** Exact window snapshot and coordinate frame. Rotation/insets/scale are pinned
 * by native geometry, not guessed from resolution or a caller's display values. */
internal data class VisualTargetProof(val image: ScreenImage, val pixelHash: String, val window: TapBounds,
    val context: String, val at: Long, val id: String = UUID.randomUUID().toString()) {
    fun metadata() = buildJsonObject {
        put("visual_id", id); put("image_width", image.width); put("image_height", image.height)
        put("window_bounds", "${window.left} ${window.top} ${window.right} ${window.bottom}")
        put("coordinate_space", "snapshot_image_pixels"); put("expires_in_ms", 60_000)
    }
    fun map(rect: TapBounds): TapBounds? {
        if (image.width <= 0 || image.height <= 0 || !TapBounds(0, 0, image.width, image.height).contains(rect)) return null
        val w = window.right - window.left; val h = window.bottom - window.top
        fun x(v: Int) = window.left + (v.toLong() * w / image.width).toInt()
        fun y(v: Int) = window.top + (v.toLong() * h / image.height).toInt()
        return TapBounds(x(rect.left), y(rect.top), x(rect.right), y(rect.bottom)).takeIf { window.contains(it) }
    }
}
/** At most two private screenshots, memory only. Retrieval rechecks live consent/
 * session and expiration; invalidation removes the old image. Never in task ledger. */
internal object VisualImageCache {
    private data class Entry(val proof: VisualTargetProof, val allowed: () -> Boolean)
    private val images = linkedMapOf<String, Entry>()
    @Synchronized fun put(proof: VisualTargetProof, allowed: () -> Boolean) {
        prune(); while (images.size >= 2) images.remove(images.keys.first())
        images[proof.id] = Entry(proof, allowed)
    }
    @Synchronized fun clear() { images.clear() }
    @Synchronized fun remove(id: String?) { if (id != null) images.remove(id) }
    @Synchronized private fun prune() {
        images.entries.removeAll { System.currentTimeMillis() - it.value.proof.at !in 0L..60_000L || !it.value.allowed() }
    }
    @Synchronized fun imageFor(result: JsonObject): ScreenImage? {
        prune()
        val data=(result["result"] as? JsonObject)?.get("data") as? JsonObject
        val visual=(data?.get("visual") ?: result["visual"]) as? JsonObject ?: return null
        val id=(visual["visual_id"] as? JsonPrimitive)?.content ?: return null
        return images[id]?.proof?.image
    }
}
