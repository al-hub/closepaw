package ai.closepaw.chatgpt.control

import ai.closepaw.model.ScreenImage
import java.util.UUID
import kotlinx.serialization.json.*

/** Exact window snapshot and coordinate frame. Rotation/insets/scale are pinned
 * by native geometry, not guessed from resolution or a caller's display values. */
internal data class VisualTargetProof(val image: ScreenImage, val pixelHash: String, val window: TapBounds,
    val context: String, val at: Long, val id: String = UUID.randomUUID().toString(), val scope: String = "app", val sourceWindowId: Int = -1,
    val sourceDisplayId: Int = -1, val rotation: Int = -1, val applicationBounds: String = "", val imeBounds: String = "",
    val sourceImeWindowId: Int = -1, val displayGeneration: Long = -1, val disclosedRegions: List<TapBounds> = emptyList(), val editorPrivacyGeneration: Long = -1,
    val sourceDisclosureAllowed: () -> Boolean = { true }) {
    fun metadata() = buildJsonObject {
        put("image_source", image.source.name.lowercase()); put("scope", scope); put("source_window_id", sourceWindowId); put("visual_id", id); put("image_width", image.width); put("image_height", image.height)
        put("source_display_id", sourceDisplayId); put("rotation", rotation)
        put("source_width", window.right-window.left); put("source_height", window.bottom-window.top)
        put("source_ime_window_id", sourceImeWindowId); put("intervening_window_changes_checked", scope == "display")
        put("application_bounds", applicationBounds); put("ime_bounds", imeBounds)
        put("verified_region_count", disclosedRegions.size); put("verified_pixel_count", disclosedRegions.sumOf { (it.right-it.left).toLong()*(it.bottom-it.top) }); put("unverified_pixels_masked", scope == "display")
        put("masked_outside_owned_regions", scope == "display"); put("owned_pixels_verified_against_isolated_windows", scope == "display")
        put("window_bounds", "${window.left} ${window.top} ${window.right} ${window.bottom}")
        put("coordinate_space", "snapshot_image_pixels"); put("expires_in_ms", if (image.source == ai.closepaw.model.ScreenImageSource.MEDIA_PROJECTION) 10_000 else 60_000)
    }
    fun map(rect: TapBounds): TapBounds? {
        if (image.width <= 0 || image.height <= 0 || !TapBounds(0, 0, image.width, image.height).contains(rect)) return null
        val w = window.right - window.left; val h = window.bottom - window.top
        fun x(v: Int) = window.left + (v.toLong() * w / image.width).toInt()
        fun y(v: Int) = window.top + (v.toLong() * h / image.height).toInt()
        return TapBounds(x(rect.left), y(rect.top), x(rect.right), y(rect.bottom)).takeIf { window.contains(it) && (scope != "display" || OwnedPixelPolicy.contains(disclosedRegions,it)) }
    }
}
/** At most two private screenshots, memory only. Retrieval rechecks live consent/
 * session and expiration; invalidation removes the old image. Never in task ledger. */
internal data class EncodedVisualImage(val mimeType: String, val data: String)
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
        images.entries.removeAll { System.currentTimeMillis() - it.value.proof.at !in 0L..60_000L || !it.value.allowed() || !it.value.proof.sourceDisclosureAllowed() }
    }
    /** Encoding can outlive revocation. Re-resolve afterward, outside the cache monitor,
     * so session/source expiration during conversion drops the response image. */
    fun encodedFor(result: JsonObject): EncodedVisualImage? {
        val image = imageFor(result) ?: return null
        if (image.width !in 1..960 || image.height !in 1..960 ||
            image.mimeType != "image/jpeg" || image.bytes.size !in 1..1_500_000) return null
        val data = java.util.Base64.getEncoder().encodeToString(image.bytes)
        return if (imageFor(result) === image) EncodedVisualImage(image.mimeType, data) else null
    }
    @Synchronized fun imageFor(result: JsonObject): ScreenImage? {
        prune()
        val data=((result["result"] as? JsonObject)?.get("data") ?: result["data"]) as? JsonObject
        val visual=(data?.get("visual") ?: result["visual"]) as? JsonObject ?: return null
        val id=(visual["visual_id"] as? JsonPrimitive)?.content ?: return null
        return images[id]?.proof?.image
    }
}
