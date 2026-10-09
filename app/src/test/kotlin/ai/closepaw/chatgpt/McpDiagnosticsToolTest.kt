package ai.closepaw.chatgpt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpDiagnosticsToolTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val handler = McpJsonRpcHandler(
        statusTool = ClosePawStatusTool(versionName = "0.1.33", versionCode = 34),
        readAppTool = ReadAppTool { ReadAppToolResult(BrowserReadDiagnostics.snapshot()) },
    )

    @Test fun diagnosticsToolIsAdvertisedAsReadOnly() {
        val response = handler.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertEquals(200, response.statusCode)
        val tools = json.parseToJsonElement(response.body!!).jsonObject["result"]!!
            .jsonObject["tools"]!!.jsonArray
        val diagnostic = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "get_diagnostics" }.jsonObject
        assertTrue(diagnostic["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(diagnostic["annotations"]!!.jsonObject["destructiveHint"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun diagnosticsUsesCanonicalInstalledVersionAndExcludesPageBody() {
        BrowserReadDiagnostics.record("samsung_internet", "content_missing", 13, true)
        val request = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_diagnostics","arguments":{}}}"""
        val response = handler.handle(request)
        assertEquals(200, response.statusCode)
        val structured = json.parseToJsonElement(response.body!!).jsonObject["result"]!!
            .jsonObject["structuredContent"]!!.jsonObject
        assertEquals("0.1.33", structured["version_name"]!!.jsonPrimitive.content)
        assertEquals("34", structured["version_code"]!!.jsonPrimitive.content)
        assertFalse(response.body!!.contains("browser page body secret"))
    }
}
