package ai.closepaw.chatgpt.control

import ai.closepaw.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectionImageCacheTest {
    private fun proof() = VisualTargetProof(
        ScreenImage(200,400,"image/jpeg",byteArrayOf(1),ScreenImageSource.MEDIA_PROJECTION),
        "hash",TapBounds(0,0,200,400),"rotation0",System.currentTimeMillis())
    @Test fun projectionSourceRevocationIsIndependentOfControlConsentAndCannotReviveCache() {
        var projectionActive = true
        val p = proof().copy(image = proof().image.copy(source = ScreenImageSource.MEDIA_PROJECTION),
            sourceDisclosureAllowed = { projectionActive })
        val result = buildJsonObject { put("data", buildJsonObject { put("visual", p.metadata()) }) }
        VisualImageCache.put(p) { true }
        assertNotNull(VisualImageCache.encodedFor(result))
        projectionActive = false
        assertNull(VisualImageCache.encodedFor(result))
        projectionActive = true
        assertNull(VisualImageCache.encodedFor(result))
    }
    @Test fun revocationDuringEncodingDropsImageAndOversizedImagesAreRefused() {
        val p = proof()
        val result = buildJsonObject { put("visual", p.metadata()) }
        var checks = 0
        VisualImageCache.put(p) { ++checks == 1 }
        assertNull(VisualImageCache.encodedFor(result))
        VisualImageCache.put(p.copy(image = p.image.copy(bytes = ByteArray(1_500_001)))) { true }
        assertNull(VisualImageCache.encodedFor(result))
        VisualImageCache.remove(p.id)
        VisualImageCache.put(p.copy(at = System.currentTimeMillis() - 60_001)) { true }
        assertNull(VisualImageCache.encodedFor(result))
    }
    @Test fun activeProjectionCannotDiscloseWithoutActiveControlSession() {
        val p = proof().copy(sourceDisclosureAllowed = { true })
        val result = buildJsonObject { put("visual", p.metadata()) }
        var controlActive = false
        VisualImageCache.put(p) { controlActive }
        assertNull(VisualImageCache.encodedFor(result))
        controlActive = true
        assertNull(VisualImageCache.encodedFor(result)) // New consent cannot revive an evicted frame.
        VisualImageCache.put(p) { controlActive }
        assertNotNull(VisualImageCache.encodedFor(result))
        controlActive = false
        assertNull(VisualImageCache.encodedFor(result))
    }

}
