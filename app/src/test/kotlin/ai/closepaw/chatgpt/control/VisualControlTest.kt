package ai.closepaw.chatgpt.control
import ai.closepaw.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class VisualControlTest {
    @Test fun cachedTaskImageIsRevokedWhenNativeDisclosureFailsEvenWithActiveSession() = runBlocking {
        var locked=false
        val session=ControlSession().also { it.start() }
        val port=object:ControlPort {
            override fun session()=session
            override fun visualDisclosureAllowed(frame:ControlFrame)=!locked
            override suspend fun capture()=ControlFrame("example",1,"same",emptyList(),touchContext="geometry")
            override suspend fun apps(filter:String)=JsonArray(emptyList())
            override suspend fun perform(frame:ControlFrame,input:JsonObject,node:ControlNode?)=ControlEffect(false,false,"unused")
            override suspend fun visual(frame:ControlFrame)=VisualTargetProof(ScreenImage(10,10,"image/jpeg",byteArrayOf(1),ScreenImageSource.ACCESSIBILITY_SCREENSHOT),"pixels",TapBounds(0,0,10,10),"geometry",System.currentTimeMillis())
        }
        val control=GenericControl(port,{true});val observed=control.observe(includeImage=true)
        val task=buildJsonObject { put("result",buildJsonObject { put("data",observed.data) }) }
        assertNotNull(VisualImageCache.imageFor(task))
        locked=true
        assertEquals(ControlSession.State.ACTIVE,session.state)
        assertNull(VisualImageCache.imageFor(task))
        locked=false
        assertNull(VisualImageCache.imageFor(task)) // Unlock cannot resurrect the revoked image.
    }
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
    @Test fun displayImageAdmitsAppGesturesButNotKeyboardImageGestures() = runBlocking {
        for (action in listOf("visual_tap", "visual_long_press", "visual_drag", "visual_scroll")) {
            val session=ControlSession().also { it.start() }; var calls=0
            val proof=VisualTargetProof(ScreenImage(100,200,"image/jpeg",byteArrayOf(1),ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
                "pixels",TapBounds(0,0,100,200),"geometry",System.currentTimeMillis(),scope="display",
                applicationBounds="0 0 100 100",imeBounds="0 100 100 200",
                disclosedRegions=listOf(TapBounds(0,0,100,200)))
            val port=object:ControlPort {
                var frame=ControlFrame("example",1,"before",emptyList(),touchContext="geometry")
                override fun session()=session
                override suspend fun capture()=frame
                override suspend fun apps(filter:String)=JsonArray(emptyList())
                override suspend fun visualDisplay(frame:ControlFrame)=proof
                override suspend fun visualKeyboard(frame:ControlFrame)=proof.copy(scope="keyboard")
                override suspend fun perform(frame:ControlFrame,input:JsonObject,node:ControlNode?):ControlEffect {
                    calls++; this.frame=frame.copy(fingerprint="after"); return ControlEffect(true,true,"gesture_completed")
                }
            }
            val control=GenericControl(port,{true})
            fun request(observed:ai.closepaw.chatgpt.operations.OperationResult)=buildJsonObject {
                put("action",action); put("observation_id",observed.data.getValue("observation_id"))
                put("visual_id",observed.data.getValue("visual").jsonObject.getValue("visual_id")); put("visual_target_confirmed",true)
                put("image_rect",JsonArray(listOf(10,10,20,20).map { JsonPrimitive(it) }))
                put("image_end_rect",JsonArray(listOf(10,40,20,50).map { JsonPrimitive(it) }))
            }
            val observed=control.observe(includeImage=true,imageScope="display"); val input=request(observed)
            assertTrue(action,control.action(input).success)
            assertFalse(control.action(input).success);assertEquals(1,calls)
            val keyboard=control.observe(includeImage=true,imageScope="keyboard")
            assertFalse(control.action(request(keyboard)).success);assertEquals(1,calls)
        }
    }
    @Test fun displayAppTargetsExcludeImeMaskedPixelsAndUnverifiedPaths() {
        val proof=VisualTargetProof(ScreenImage(100,200,"image/jpeg",byteArrayOf(1),ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
            "pixels",TapBounds(0,0,100,200),"geometry",0,scope="display",
            applicationBounds="0 0 100 200",imeBounds="0 100 100 200",
            disclosedRegions=listOf(TapBounds(0,0,100,200)))
        assertEquals(TapBounds(10,10,20,20),VisualActionPolicy.target(proof,"visual_tap",TapBounds(10,10,20,20)))
        assertNull(VisualActionPolicy.target(proof,"visual_tap",TapBounds(10,110,20,120)))
        assertNull(VisualActionPolicy.target(proof.copy(applicationBounds=""),"visual_tap",TapBounds(10,10,20,20)))
        val gap=proof.copy(disclosedRegions=listOf(TapBounds(0,0,100,30),TapBounds(0,40,100,100)))
        assertNull(VisualActionPolicy.target(gap,"visual_tap",TapBounds(10,30,20,40)))
        assertFalse(VisualActionPolicy.pathAllowed(gap,TapBounds(10,10,20,20),TapBounds(10,50,20,60)))
        assertTrue(VisualActionPolicy.pathAllowed(proof,TapBounds(10,10,20,20),TapBounds(10,50,20,60)))
        assertFalse(VisualActionPolicy.scopeAllowed("visual_key","app"))
        assertFalse(VisualActionPolicy.scopeAllowed("visual_tap","unknown"))
    }

    @Test fun geometryMismatchDiagnosticFailsClosedWithoutCaptureFallback() = runBlocking {
        for ((scope, reason) in listOf(
            "app" to "screenshot_buffer_geometry_mismatch",
            "keyboard" to "screenshot_buffer_geometry_mismatch",
            "display" to "screenshot_buffer_geometry_mismatch",
            "display" to "display_isolated_screenshot_buffer_geometry_mismatch"
        )) {
            val session = ControlSession().also { it.start() }
            var failCapture = false
            var dispatches = 0
            val captures = mutableListOf<String>()
            val proof = VisualTargetProof(
                ScreenImage(100,100,"image/jpeg",byteArrayOf(1),ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
                "pixels",TapBounds(0,0,100,100),"geometry",System.currentTimeMillis(),scope=scope)
            val port = object : ControlPort {
                override fun session() = session
                override suspend fun capture() = ControlFrame("example",1,"same",emptyList(),touchContext="geometry")
                override suspend fun apps(filter:String) = JsonArray(emptyList())
                private fun image(requestedScope:String): VisualTargetProof? {
                    captures.add(requestedScope)
                    return if (failCapture) null else proof
                }
                override suspend fun visual(frame:ControlFrame) = image("app")
                override suspend fun visualKeyboard(frame:ControlFrame) = image("keyboard")
                override suspend fun visualDisplay(frame:ControlFrame) = image("display")
                override fun visualFailure() = reason
                override suspend fun perform(frame:ControlFrame,input:JsonObject,node:ControlNode?):ControlEffect {
                    dispatches++
                    return ControlEffect(true,true,"gesture_completed")
                }
            }
            val control = GenericControl(port,{true})
            val previous = control.observe(includeImage=true,imageScope=scope)
            assertTrue(previous.success)
            val staleAction = buildJsonObject {
                put("action","visual_tap")
                put("observation_id",previous.data.getValue("observation_id"))
                put("visual_id",previous.data.getValue("visual").jsonObject.getValue("visual_id"))
                put("visual_target_confirmed",true)
                put("image_rect",JsonArray(listOf(10,10,20,20).map { JsonPrimitive(it) }))
            }
            val previousTask = buildJsonObject { put("result",buildJsonObject { put("data",previous.data) }) }
            assertNotNull(VisualImageCache.imageFor(previousTask))
            failCapture = true
            captures.clear()
            val failed = control.observe(includeImage=true,imageScope=scope)
            assertFalse(reason,failed.success)
            assertEquals(reason,failed.summary)
            assertEquals(JsonPrimitive(reason),failed.data["reason_code"])
            assertEquals(JsonPrimitive(false),failed.data["action_attempted"])
            assertEquals(JsonPrimitive(false),failed.data["outcome_unknown"])
            assertEquals(listOf(scope),captures)
            assertNull(VisualImageCache.imageFor(previousTask))
            assertFalse(failed.data.containsKey("visual"))
            assertFalse(failed.data.containsKey("observation_id"))
            assertFalse(failed.data.containsKey("nodes"))
            val rejected = control.action(staleAction)
            assertFalse(rejected.success)
            assertEquals("observation_required",rejected.summary)
            assertEquals(0,dispatches)
        }
    }

}
