package ai.closepaw.chatgpt.control
import ai.closepaw.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class VisualControlTest {
    @Test fun actualVisualActionRequiresItsOwnFreshImageAndNeverReplays() = runBlocking {
        class Port : ControlPort {
            val session = ControlSession().also { it.start() }; var calls=0
            var frame = ControlFrame("example",1,"before",emptyList(),touchContext="geometry")
            override fun session() = session
            override suspend fun capture() = frame
            override suspend fun apps(filter:String)=JsonArray(emptyList())
            override suspend fun visual(frame:ControlFrame) = VisualTargetProof(ScreenImage(100,100,"image/jpeg",byteArrayOf(1),ScreenImageSource.ACCESSIBILITY_SCREENSHOT),"pixels",TapBounds(0,20,100,120),"geometry",System.currentTimeMillis())
            override suspend fun perform(frame:ControlFrame,input:JsonObject,node:ControlNode?):ControlEffect {
                calls++; this.frame = frame.copy(fingerprint="after"); return ControlEffect(true,true,"gesture_completed")
            }
        }
        val p=Port();val c=GenericControl(p,{true});val observed=c.observe(includeImage=true)
        val input=buildJsonObject { put("action","visual_tap"); put("observation_id",observed.data.getValue("observation_id")); put("visual_id",observed.data.getValue("visual").jsonObject.getValue("visual_id")); put("visual_target_confirmed",true);put("image_rect",JsonArray(listOf(10,10,20,20).map{JsonPrimitive(it)})) }
        assertTrue(c.action(input).success);assertFalse(c.action(input).success);assertEquals(1,p.calls)
        val fresh=c.observe(includeImage=true)
        val refused=c.action(JsonObject(input + mapOf("observation_id" to fresh.data.getValue("observation_id"))))
        assertFalse(refused.success);assertEquals(1,p.calls)
    }
}
