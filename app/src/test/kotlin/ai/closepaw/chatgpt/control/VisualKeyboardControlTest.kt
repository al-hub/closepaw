package ai.closepaw.chatgpt.control

import ai.closepaw.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VisualKeyboardControlTest {
    private class Port : ControlPort {
        val session = ControlSession().also { it.start() }
        val field = ControlNode(1,text="A",editable=true,focused=true,focusable=true,
            selectionStart=1,selectionEnd=1,sourceWindowId=1,sourcePackage="app")
        val key = ControlNode(2,text="a",bounds="10 110 30 140",sourceWindowId=2,sourcePackage="ime",
            keyboardKey="a",visualKeyCandidate=true)
        var frame = ControlFrame("app",1,"before",listOf(field,key),touchContext="geometry",
            keyboard=ControlKeyboard(2,"ime","ime/.Keyboard"),inputTargetId=1,inputTarget=field)
        var calls=0; var reflectEffect=true
        override fun session() = session
        override suspend fun apps(filter:String) = JsonArray(emptyList())
        override suspend fun capture() = frame
        override suspend fun visual(frame:ControlFrame) = proof("app",1)
        override suspend fun visualKeyboard(frame:ControlFrame) = proof("keyboard",2)
        override suspend fun visualDisplay(frame:ControlFrame) = proof("display",-1)
        private fun proof(scope:String,id:Int) = VisualTargetProof(ScreenImage(100,100,"image/jpeg",byteArrayOf(1),ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
            "pixels",TapBounds(0,100,100,200),"geometry",System.currentTimeMillis(),scope=scope,sourceWindowId=id)
        override suspend fun perform(frame:ControlFrame,input:JsonObject,node:ControlNode?):ControlEffect {
            calls++
            if (reflectEffect) {
                val after=field.copy(text="Aa",selectionStart=2,selectionEnd=2)
                this.frame=this.frame.copy(fingerprint="after",nodes=listOf(after,key),inputTarget=after)
            }
            return ControlEffect(true,true,"gesture_completed")
        }
    }
    private fun request(observation: ai.closepaw.chatgpt.operations.OperationResult) = buildJsonObject {
        put("action","visual_key"); put("node_id",2)
        put("observation_id",observation.data.getValue("observation_id"))
        put("visual_id",observation.data.getValue("visual").jsonObject.getValue("visual_id"))
        put("visual_target_confirmed",true)
        put("image_rect",JsonArray(listOf(10,10,30,40).map { JsonPrimitive(it) }))
    }
    @Test fun separateImeProofRequiresExactEffectAndConsumesObservation() = runBlocking {
        val p=Port();val c=GenericControl(p,{true})
        val observed=c.observe(includeImage=true,imageScope="keyboard")
        val input=request(observed)
        val result=c.action(input)
        assertTrue(result.success);assertEquals(JsonPrimitive(true),result.data["app_result_verified"])
        assertFalse(c.action(input).success);assertEquals(1,p.calls)
    }
    @Test fun applicationImageCannotAuthorizeKeyboardAndUnknownIsNeverReplayed() = runBlocking {
        val p=Port();val c=GenericControl(p,{true})
        val app=c.observe(includeImage=true)
        assertFalse(c.action(request(app)).success);assertEquals(0,p.calls)
        p.reflectEffect=false
        val ime=c.observe(includeImage=true,imageScope="keyboard");val input=request(ime)
        val result=c.action(input)
        assertFalse(result.success);assertEquals(JsonPrimitive(true),result.data["outcome_unknown"])
        assertFalse(c.action(input).success);assertEquals(1,p.calls)
    }
    @Test fun changedImeOrGeometryCannotDispatch() = runBlocking {
        val p=Port();val c=GenericControl(p,{true})
        val observed=c.observe(includeImage=true,imageScope="keyboard")
        p.frame=p.frame.copy(keyboard=p.frame.keyboard!!.copy(inputMethodId="other/.Ime"))
        val result=c.action(request(observed))
        assertFalse(result.success);assertEquals(0,p.calls)
    }
    @Test fun explicitDisplayImageAuthorizesOnlyVerifiedImeKeyAndNoReplay() = runBlocking {
        val p=Port(); val c=GenericControl(p,{true})
        assertFalse(c.observe(imageScope="display").success)
        val observed=c.observe(includeImage=true,imageScope="display")
        assertTrue(observed.success)
        val input=request(observed)
        assertTrue(c.action(input).success)
        assertFalse(c.action(input).success); assertEquals(1,p.calls)
        val second=c.observe(includeImage=true,imageScope="display")
        val tap=buildJsonObject { request(second).forEach { (k,v)->put(k,v) }; put("action","visual_tap") }
        assertFalse(c.action(tap).success); assertEquals(1,p.calls)
    }

}
