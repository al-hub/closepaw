package ai.closepaw.chatgpt.control

import ai.closepaw.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ImeSurfaceGeometryTest {
    private val hash = "a".repeat(64)
    private val touch = TapBounds(0,1798,1440,3120)
    private fun evidence() = buildJsonObject {
        put("schema",1); put("window_id",2); put("token","android.os.BinderProxy@abc")
        put("surface","VRI-InputMethod\$_123#456"); put("serial","fixture:12345")
        put("frame",JsonArray(listOf(0,127,1440,3120).map(::JsonPrimitive)))
        put("touch",JsonArray(listOf(0,1798,1440,3120).map(::JsonPrimitive)))
        put("translation",JsonArray(listOf(0,127).map(::JsonPrimitive)))
        put("width",1440); put("height",2993); put("rotation",0); put("challenge_sha256",hash)
    }
    @Test fun exactNativeEvidenceDefinesFullBufferCoordinatesAndOnlyTouchableKeys() {
        val e = requireNotNull(ImeSurfaceGeometry.parse(evidence().toString(),2,touch,hash))
        assertEquals(TapBounds(0,127,1440,3120),e.frame)
        val p = VisualTargetProof(ScreenImage(1440,2993,"image/jpeg",byteArrayOf(1),ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
            "pixels",e.frame,"native",0,scope="keyboard",sourceWindowId=2,disclosedRegions=listOf(touch))
        assertEquals(TapBounds(100,1800,150,1850),p.map(TapBounds(100,1673,150,1723)))
        assertNull(p.map(TapBounds(100,1600,150,1700)))
        assertNull(p.map(TapBounds(0,0,100,100)))
    }
    @Test fun missingWrongOrChangedAuthorityNeverParses() {
        assertNull(ImeSurfaceGeometry.parse(evidence().toString(),3,touch,hash))
        assertNull(ImeSurfaceGeometry.parse(evidence().toString(),2,touch,"b".repeat(64)))
        assertNull(ImeSurfaceGeometry.parse(evidence().toString(),2,touch.copy(top=1799),hash))
        for ((key,value) in listOf(
            "schema" to JsonPrimitive(2), "rotation" to JsonPrimitive(1),
            "width" to JsonPrimitive(1441), "height" to JsonPrimitive("2993"),
            "token" to JsonPrimitive("unjoined"), "surface" to JsonPrimitive("prefix-only"),
            "translation" to JsonArray(listOf(JsonPrimitive(0),JsonPrimitive(128))),
            "unexpected" to JsonPrimitive(true),
        )) {
            val changed = JsonObject(evidence().toMutableMap().apply { put(key,value) })
            assertNull(key,ImeSurfaceGeometry.parse(changed.toString(),2,touch,hash))
        }
        for (key in evidence().keys) assertNull(key,ImeSurfaceGeometry.parse(JsonObject(evidence()-key).toString(),2,touch,hash))
    }
    @Test fun challengeDoesNotChangeSourceFingerprintButWindowReplacementDoes() {
        val a = requireNotNull(ImeSurfaceGeometry.parse(evidence().toString(),2,touch,hash))
        assertEquals(a.fingerprint,a.copy(challengeHash="b".repeat(64)).fingerprint)
        assertNotEquals(a.fingerprint,a.copy(surface="VRI-InputMethod\$_123#457").fingerprint)
        assertNotEquals(a.fingerprint,a.copy(token="android.os.BinderProxy@def").fingerprint)
    }
}
