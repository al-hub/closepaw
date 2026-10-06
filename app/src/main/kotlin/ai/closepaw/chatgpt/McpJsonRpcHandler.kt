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
                add(buildJsonObject {
                    put("name", STATUS_TOOL_NAME)
                    put("description", "Check whether the ClosePaw Android app is reachable and responding. Read-only.")
                    put("inputSchema", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {})
                        put("additionalProperties", false)
                    })
                    put("annotations", buildJsonObject {
                        put("readOnlyHint", true)
                        put("destructiveHint", false)
                        put("idempotentHint", true)
                        put("openWorldHint", false)
                    })
                })
            })
        }
    )

    private fun callTool(id: JsonElement, request: JsonObject): McpHttpResponse {
        val name = request["params"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            ?: return jsonError(id, -32602, "Missing tool name")
        if (name != STATUS_TOOL_NAME) {
            return jsonError(id, -32602, "Unknown tool: $name")
        }

        val snapshot = statusTool.snapshot()
        return jsonResult(
            id,
            buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", snapshot["summary"]?.jsonPrimitive?.content ?: "ClosePaw is responding.")
                    })
                })
                put("structuredContent", snapshot)
                put("isError", false)
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
        const val DEFAULT_PROTOCOL_VERSION = "2025-06-18"
    }
}
