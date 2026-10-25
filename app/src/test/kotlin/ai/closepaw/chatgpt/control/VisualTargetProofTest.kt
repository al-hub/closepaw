package ai.closepaw.chatgpt.control
import ai.closepaw.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class VisualTargetProofTest {
    private fun proof() = VisualTargetProof(ScreenImage(200,400,"image/jpeg",byteArrayOf(1),ScreenImageSource.ACCESSIBILITY_SCREENSHOT),"hash",TapBounds(40,100,440,900),"rotation0",System.currentTimeMillis())
    @Test fun imageScaleAndWindowInsetsMapExactlyWithoutGuessingDisplayCoordinates() {
        val p = proof()
        assertEquals(TapBounds(60,140,100,220),p.map(TapBounds(10,20,30,60)))
        for (rect in listOf(TapBounds(-1,0,10,10),TapBounds(0,0,201,20),TapBounds(10,10,10,20))) assertNull(p.map(rect))
    }
    @Test fun screenshotCannotBeRetrievedAfterRevocationOrSingleUseInvalidation() {
        val p = proof(); var allowed = true
        val data = buildJsonObject { put("result",buildJsonObject { put("data",buildJsonObject { put("visual",p.metadata()) }) }) }
        VisualImageCache.put(p) { allowed }; assertNotNull(VisualImageCache.imageFor(data))
        allowed = false; assertNull(VisualImageCache.imageFor(data))
        VisualImageCache.put(p) { true }; VisualImageCache.remove(p.id); assertNull(VisualImageCache.imageFor(data))
    }
    @Test fun windowRotationOrGeometryChangeRejectsVisualActions() {
        val before = ControlFrame("example",1,"same",emptyList(),touchContext="rotation0")
        assertNotNull(GenericControlActionPolicy.refusal(before,before.copy(touchContext="rotation1"),"visual_drag"))
        assertNotNull(GenericControlActionPolicy.refusal(before,before.copy(fingerprint="changed"),"visual_tap"))
    }
    @Test fun queryAndTaskEnvelopesDeliverOnlyLiveProofImages() {
        val p = proof(); var active = true
        val query = buildJsonObject { put("status", "succeeded"); put("data", buildJsonObject { put("visual", p.metadata()) }) }
        val task = buildJsonObject { put("result", buildJsonObject { put("data", buildJsonObject { put("visual", p.metadata()) }) }) }
        VisualImageCache.put(p) { active }
        assertSame(p.image, VisualImageCache.imageFor(query)); assertSame(p.image, VisualImageCache.imageFor(task))
        active = false
        assertNull(VisualImageCache.imageFor(query)); assertNull(VisualImageCache.imageFor(task))
    }
}
