package ai.closepaw.chatgpt.control

import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import ai.closepaw.security.ImageRevocationAuthority
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * JVM security/regression tests for the private screenshot store behind issue #165:
 * at most two images, memory only, expiration-bounded, and every retrieval rechecks
 * live consent/revocation. A revoked, expired or evicted proof can never be
 * resurrected by a later state change, and malformed envelopes yield nothing.
 */
class VisualImageCacheRevocationTest {

    @Before
    fun cleanCacheState() {
        VisualImageCache.clear()
        // JVM fixture: model an explicit active image-consent epoch; production requires OS consent.
        ImageRevocationAuthority.grantConsent()
    }

    @After
    fun cleanupCacheState() {
        VisualImageCache.clear()
        ImageRevocationAuthority.revokeConsent()
    }

    private fun proof(atMs: Long = System.currentTimeMillis(), imageSeed: Byte = 1) =
        VisualTargetProof(
            ScreenImage(200, 400, "image/jpeg", byteArrayOf(imageSeed, 2, 3), ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
            "hash",
            TapBounds(40, 100, 440, 900),
            "rotation0",
            atMs,
        )

    private fun envelope(p: VisualTargetProof) = buildJsonObject {
        put("status", "succeeded")
        put("data", buildJsonObject { put("visual", p.metadata()) })
    }

    private fun envelopeNested(p: VisualTargetProof) = buildJsonObject {
        put("result", buildJsonObject {
            put("data", buildJsonObject { put("visual", p.metadata()) })
        })
    }

    private fun envelopeTopLevel(p: VisualTargetProof) = buildJsonObject {
        put("visual", p.metadata())
    }

    // ── Retrieval across all supported envelope shapes ───────────────

    @Test
    fun `live proof resolves from every supported envelope shape`() {
        val p = proof()
        VisualImageCache.put(p) { true }
        assertSame(p.image, VisualImageCache.imageFor(envelope(p)))
        assertSame(p.image, VisualImageCache.imageFor(envelopeNested(p)))
        assertSame(p.image, VisualImageCache.imageFor(envelopeTopLevel(p)))
    }

    // ── Revocation is permanent at retrieval time ────────────────────

    @Test
    fun `revoked proof cannot be resurrected when the gate later reopens`() {
        val p = proof()
        var live = true
        VisualImageCache.put(p) { live }
        assertSame(p.image, VisualImageCache.imageFor(envelope(p)))

        // Revoked once → pruned for good.
        live = false
        assertNull(VisualImageCache.imageFor(envelope(p)))

        // Re-opening the gate afterwards must not bring the buffer back.
        live = true
        assertNull(VisualImageCache.imageFor(envelope(p)))
        assertNull(VisualImageCache.imageFor(envelopeNested(p)))
        assertNull(VisualImageCache.imageFor(envelopeTopLevel(p)))
    }

    @Test
    fun `cache invalidation drops every buffer in one go`() {
        val p1 = proof(imageSeed = 1)
        val p2 = proof(imageSeed = 2)
        VisualImageCache.put(p1) { true }
        VisualImageCache.put(p2) { true }
        assertSame(p2.image, VisualImageCache.imageFor(envelope(p2)))

        VisualImageCache.clear()
        assertNull(VisualImageCache.imageFor(envelope(p1)))
        assertNull(VisualImageCache.imageFor(envelope(p2)))
        assertNull(VisualImageCache.imageFor(envelopeTopLevel(p1)))
    }

    @Test
    fun `single-buffer removal removes only its own id and tolerates null`() {
        val p1 = proof(imageSeed = 1)
        val p2 = proof(imageSeed = 2)
        VisualImageCache.put(p1) { true }
        VisualImageCache.put(p2) { true }

        VisualImageCache.remove(null)
        assertSame(p1.image, VisualImageCache.imageFor(envelope(p1)))
        assertSame(p2.image, VisualImageCache.imageFor(envelope(p2)))

        VisualImageCache.remove(p2.id)
        assertNull(VisualImageCache.imageFor(envelope(p2)))
        assertSame(p1.image, VisualImageCache.imageFor(envelope(p1)))
    }

    // ── Expiration ───────────────────────────────────────────────────

    @Test
    fun `proof past its 60s lifetime is pruned at retrieval`() {
        val expired = proof(atMs = System.currentTimeMillis() - 60_001)
        VisualImageCache.put(expired) { true }
        assertNull(VisualImageCache.imageFor(envelope(expired)))
    }

    @Test
    fun `future-dated proof is treated as expired and never delivered`() {
        val future = proof(atMs = System.currentTimeMillis() + 10_000)
        VisualImageCache.put(future) { true }
        assertNull(VisualImageCache.imageFor(envelope(future)))
    }

    @Test
    fun `expired buffers do not crowd out live ones`() {
        val expired = proof(atMs = System.currentTimeMillis() - 120_000, imageSeed = 1)
        val live = proof(imageSeed = 2)
        VisualImageCache.put(expired) { true }
        VisualImageCache.put(live) { true }
        assertSame(live.image, VisualImageCache.imageFor(envelope(live)))
        assertNull(VisualImageCache.imageFor(envelope(expired)))
    }

    // ── Capacity: at most two buffers, memory only ───────────────────

    @Test
    fun `filling the cache evicts oldest and keeps the newest two`() {
        val p1 = proof(imageSeed = 1)
        val p2 = proof(imageSeed = 2)
        val p3 = proof(imageSeed = 3)
        VisualImageCache.put(p1) { true }
        VisualImageCache.put(p2) { true }
        VisualImageCache.put(p3) { true }

        assertNull(VisualImageCache.imageFor(envelope(p1)))
        assertSame(p2.image, VisualImageCache.imageFor(envelope(p2)))
        assertSame(p3.image, VisualImageCache.imageFor(envelope(p3)))
    }

    @Test
    fun `eviction boundary is exactly two entries`() {
        val p1 = proof(imageSeed = 1)
        val p2 = proof(imageSeed = 2)
        val p3 = proof(imageSeed = 3)
        VisualImageCache.put(p1) { true }
        VisualImageCache.put(p2) { true }
        assertSame(p1.image, VisualImageCache.imageFor(envelope(p1)))
        assertSame(p2.image, VisualImageCache.imageFor(envelope(p2)))
        VisualImageCache.put(p3) { true }
        assertNull(VisualImageCache.imageFor(envelope(p1)))
    }

    // ── Malformed / unknown requests never resolve a buffer ──────────

    @Test
    fun `malformed envelopes resolve nothing even when a live proof exists`() {
        val p = proof()
        VisualImageCache.put(p) { true }

        // No visual object anywhere.
        assertNull(VisualImageCache.imageFor(buildJsonObject { put("status", "succeeded") }))
        // visual is not an object.
        assertNull(VisualImageCache.imageFor(buildJsonObject {
            put("data", buildJsonObject { put("visual", "not-an-object") })
        }))
        // visual_id missing inside visual.
        assertNull(VisualImageCache.imageFor(buildJsonObject {
            put("data", buildJsonObject { put("visual", buildJsonObject { put("scope", "app") }) })
        }))
        // Unknown visual_id.
        assertNull(VisualImageCache.imageFor(buildJsonObject {
            put("data", buildJsonObject { put("visual", buildJsonObject { put("visual_id", "unknown-id") }) })
        }))
        // Live proof is untouched by the malformed lookups.
        assertSame(p.image, VisualImageCache.imageFor(envelope(p)))
    }
}
