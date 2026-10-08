package ai.closepaw.chatgpt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class McpHttpResponse(
    val statusCode: Int,
    val body: String? = null,
    val contentType: String = "application/json",
)

internal class McpJsonRpcHandler(
    private val statusTool: ClosePawStatusTool,
    private val readAppTool: ReadAppTool? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun handle(body: String): McpHttpResponse {
        val request = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            return jsonError(JsonNull, -32700, "Parse error")
        }

        val id = request["id"] ?: JsonNull
        val method = request["method"]?.jsonPrimitive?.content
            ?: return jsonError(id, -32600, "Invalid Request")

        return when (method) {
            "initialize" -> initialize(id, request)
            "notifications/initialized" -> McpHttpResponse(statusCode = 204)
            "ping" -> jsonResult(id, buildJsonObject {})
            "tools/list" -> listTools(id)
            "tools/call" -> callTool(id, request)
            else -> jsonError(id, -32601, "Method not found")
        }
    }

    private fun initialize(id: JsonElement, request: JsonObject): McpHttpResponse {
        val requestedProtocol = request["params"]?.jsonObject
            ?.get("protocolVersion")?.jsonPrimitive?.content
            ?: DEFAULT_PROTOCOL_VERSION
        return jsonResult(
            id,
            buildJsonObject {
                put("protocolVersion", requestedProtocol)
                put("capabilities", buildJsonObject {
                    put("tools", buildJsonObject { put("listChanged", false) })
                })
                put("serverInfo", buildJsonObject {
                    put("name", "ClosePaw")
                    put("version", statusTool.snapshot()["version_name"]?.jsonPrimitive?.content ?: "unknown")
                })
            }
        )
    }

    private fun listTools(id: JsonElement): McpHttpResponse = jsonResult(
        id,
        buildJsonObject {
            put("tools", buildJsonArray {
                add(statusToolDefinition())
                if (readAppTool != null) add(readAppToolDefinition())
            })
        }
    )

    private fun statusToolDefinition(): JsonObject = buildJsonObject {
        put("name", STATUS_TOOL_NAME)
        put("description", "Check whether the ClosePaw Android app is reachable and responding. Read-only.")
        put("inputSchema", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
            put("additionalProperties", false)
        })
        put("annotations", readOnlyAnnotations())
    }

    private fun readAppToolDefinition(): JsonObject = buildJsonObject {
        put("name", READ_APP_TOOL_NAME)
        put(
            "description",
            "Read a supported Android browser using Accessibility text plus a bounded screenshot for visual fallback. " +
                "No Shizuku, ADB, or CDP is required. The tool may launch the app but never types, submits, deletes, purchases, or sends."
        )
        put("inputSchema", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("app", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("samsung_internet"))
                        add(kotlinx.serialization.json.JsonPrimitive("chrome"))
                    })
                    put("description", "Target browser: samsung_internet or chrome.")
                })
            })
            put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("app")) })
            put("additionalProperties", false)
        })
        put("annotations", readOnlyAnnotations())
    }

    private fun readOnlyAnnotations(): JsonObject = buildJsonObject {
        put("readOnlyHint", true)
        put("destructiveHint", false)
        put("idempotentHint", true)
        put("openWorldHint", false)
    }

    private fun callTool(id: JsonElement, request: JsonObject): McpHttpResponse {
        val params = request["params"]?.jsonObject
            ?: return jsonError(id, -32602, "Missing params")
        val name = params["name"]?.jsonPrimitive?.content
            ?: return jsonError(id, -32602, "Missing tool name")

        return when (name) {
            STATUS_TOOL_NAME -> toolResult(id, statusTool.snapshot())
            READ_APP_TOOL_NAME -> {
                val tool = readAppTool
                    ?: return jsonError(id, -32602, "Unknown tool: $name")
                val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
                val app = arguments["app"]?.jsonPrimitive?.content
                    ?: return jsonError(id, -32602, "Missing app")
                toolResult(id, tool.read(app))
            }
            else -> jsonError(id, -32602, "Unknown tool: $name")
        }
    }

    private fun toolResult(id: JsonElement, result: ReadAppToolResult): McpHttpResponse {
        val structured = result.structured
        val summary = structured["summary"]?.jsonPrimitive?.content ?: "ClosePaw responded."
        val content = structured["content"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?: summary
        val isError = structured["status"]?.jsonPrimitive?.content == "failed"
        return jsonResult(
            id,
            buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", content)
                    })
                    val imageData = result.imageBase64
                    val imageMimeType = result.imageMimeType
                    if (!imageData.isNullOrBlank() && !imageMimeType.isNullOrBlank()) {
                        add(buildJsonObject {
                            put("type", "image")
                            put("data", imageData)
                            put("mimeType", imageMimeType)
                        })
                    }
                })
                put("structuredContent", structured)
                put("isError", isError)
            }
        )
    }

    private fun toolResult(id: JsonElement, structured: JsonObject): McpHttpResponse {
        val summary = structured["summary"]?.jsonPrimitive?.content ?: "ClosePaw responded."
        val content = structured["content"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?: summary
        val isError = structured["status"]?.jsonPrimitive?.content == "failed"
        return jsonResult(
            id,
            buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", content)
                    })
                })
                put("structuredContent", structured)
                put("isError", isError)
            }
        )
    }

    private fun jsonResult(id: JsonElement, result: JsonObject): McpHttpResponse =
        McpHttpResponse(
            statusCode = 200,
            body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }.toString()
        )

    private fun jsonError(id: JsonElement, code: Int, message: String): McpHttpResponse =
        McpHttpResponse(
            statusCode = 200,
            body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("error", buildJsonObject {
                    put("code", code)
                    put("message", message)
                })
            }.toString()
        )

    companion object {
        const val STATUS_TOOL_NAME = "get_status"
        const val READ_APP_TOOL_NAME = "read_app"
        const val DEFAULT_PROTOCOL_VERSION = "2025-06-18"
    }
}
