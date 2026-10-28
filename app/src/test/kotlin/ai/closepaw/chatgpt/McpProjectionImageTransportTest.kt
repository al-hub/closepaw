package ai.closepaw.chatgpt

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpProjectionImageTransportTest {
    private fun handler(call: (String, JsonObject) -> JsonObject) =
        McpJsonRpcHandler(ClosePawStatusTool("test", 1), operationTools = McpOperationTools(call))

    @Test fun projectionImageTransportChecksSourceLeaseForQueriesAndTaskReplay() {
        for (task in listOf(false, true)) {
            var sourceActive = true
            val proof = ai.closepaw.chatgpt.control.VisualTargetProof(
                ai.closepaw.model.ScreenImage(2,3,"image/jpeg",byteArrayOf(1,2,3), ai.closepaw.model.ScreenImageSource.MEDIA_PROJECTION),
                "pixels", ai.closepaw.chatgpt.control.TapBounds(0,0,2,3), "window", System.currentTimeMillis(),
                scope = "display", sourceDisclosureAllowed = { sourceActive })
            ai.closepaw.chatgpt.control.VisualImageCache.put(proof) { true }
            val data = buildJsonObject { put("visual", proof.metadata()) }
            val handler = handler { _, _ -> buildJsonObject {
                put("status", "succeeded")
                if (task) put("result", buildJsonObject { put("data", data) }) else put("data", data)
            } }
            val request = if (task)
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_task","arguments":{"task_id":"projection"}}}"""
            else """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"query","arguments":{"operation_id":"android.screen.observe","operation_version":1,"schema_hash":"hash","input_json":"{}"}}}"""
            fun response() = Json.parseToJsonElement(handler.handle(request).body!!).jsonObject.getValue("result").jsonObject
            assertEquals("AQID", response().getValue("content").jsonArray[1].jsonObject.getValue("data").jsonPrimitive.content)
            sourceActive = false
            val revoked = response()
            assertEquals(1, revoked.getValue("content").jsonArray.size)
            val structured = revoked.getValue("structuredContent").jsonObject
            val deliveredData = (if (task) structured.getValue("result").jsonObject else structured).getValue("data").jsonObject
            assertFalse(deliveredData.getValue("image_included").jsonPrimitive.boolean)
            sourceActive = true
            assertEquals(1, response().getValue("content").jsonArray.size)
        }
    }

}
