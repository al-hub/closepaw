package ai.closepaw.chatgpt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put

internal data class McpHttpResponse(
    val statusCode: Int,
    val body: String? = null,
    val contentType: String = "application/json",
    val imageEpoch: Long? = null,
)

internal class McpJsonRpcHandler(
    private val statusTool: ClosePawStatusTool,
    private val readAppTool: ReadAppTool? = null,
    private val operationTools: McpOperationTools? = null,
    private val runtimeInfoEnabled: Boolean = false,
    private val extraDiagnostics: () -> JsonObject = { JsonObject(emptyMap()) },
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun handle(body: String): McpHttpResponse {
        val request = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            return jsonError(JsonNull, -32700, "Parse error")
        }

        val id = request["id"] ?: JsonNull
        val method = (request["method"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return jsonError(id, -32600, "Invalid Request")

        return try { when (method) {
            "initialize" -> initialize(id, request)
            "notifications/initialized" -> McpHttpResponse(statusCode = 204)
            "ping" -> jsonResult(id, buildJsonObject {})
            "tools/list" -> listTools(id)
            "tools/call" -> callTool(id, request)
            else -> jsonError(id, -32601, "Method not found")
        } } catch (_: Exception) { jsonError(id, -32602, "Invalid request arguments") }
    }

    private fun initialize(id: JsonElement, request: JsonObject): McpHttpResponse {
        val requestedProtocol = request["params"]?.jsonObject
            ?.get("protocolVersion")?.jsonPrimitive?.content
            ?: DEFAULT_PROTOCOL_VERSION
        return jsonResult(
            id,
            buildJsonObject {
                put("protocolVersion", if (requestedProtocol in SUPPORTED_PROTOCOL_VERSIONS) requestedProtocol else DEFAULT_PROTOCOL_VERSION)
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

    private fun toolDefinitions() = buildJsonArray {
                add(statusToolDefinition())
                if (operationTools != null) McpOperationContract.names.forEach { add(McpOperationContract.definition(it)) }
                if (readAppTool != null) {
                    add(readAppToolDefinition())
                    add(diagnosticsToolDefinition())
                }
                if (runtimeInfoEnabled) add(runtimeInfoToolDefinition())
    }

    private fun listTools(id: JsonElement): McpHttpResponse = jsonResult(id, buildJsonObject {
        put("tools", toolDefinitions())
    })

    private fun runtimeInfoToolDefinition() = buildJsonObject {
        put("name", RUNTIME_INFO_TOOL_NAME)
        put("description", "Read the installed MCP tool contract fingerprint and optional redacted diagnostics. No app launch or input. Use existing get_status for fallback discovery if this newly added tool is unavailable in the client.")
        put("inputSchema", buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            put("properties", buildJsonObject {
                put("include_diagnostics", buildJsonObject { put("type", "boolean"); put("default", false) })
            })
        })
        put("annotations", readOnlyAnnotations())
    }

    private fun runtimeInfo(arguments: JsonObject): JsonObject {
        require(arguments.keys.all { it == "include_diagnostics" })
        val include = arguments["include_diagnostics"]?.let {
            require(it is kotlinx.serialization.json.JsonPrimitive && !it.isString && it.content in setOf("true", "false"))
            it.content == "true"
        } ?: false
        val definitions = toolDefinitions()
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(definitions.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return buildJsonObject {
            put("status", "succeeded"); put("version_name", statusTool.snapshot().getValue("version_name"))
            put("version_code", statusTool.snapshot().getValue("version_code"))
            put("tool_count", definitions.size); put("tool_schema_hash", hash)
            put("tools", buildJsonArray { definitions.forEach { add(it.jsonObject.getValue("name")) } })
            put("runtime_info_version", 1)
            put("client_refresh_verified", false)
            if (include) put("diagnostics", JsonObject(BrowserReadDiagnostics.snapshot() + extraDiagnostics()))
        }
    }

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
            "Read a single visible-screen snapshot from an app supported by ClosePaw: " +
                "Accessibility text, local Korean screenshot OCR fallback, and one bounded JPEG. " +
                "Use get_status to discover supported app identifiers. ClosePaw decides support and execution at runtime. Not a full-page read. Summarize only captured visible content; if the body is missing, say so. " +
                "Never present outside web search as captured screen content. " +
                "No Shizuku, ADB, or CDP is required. The tool may launch the selected app but never types, submits, deletes, purchases, or sends."
        )
        put("inputSchema", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("app", buildJsonObject {
                    put("type", "string")
                    put("description", "Target app identifier. Discover supported apps with get_status; ClosePaw validates support at runtime.")
                })
            })
            put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("app")) })
            put("additionalProperties", false)
        })
        put("annotations", readOnlyAnnotations())
    }

    private fun diagnosticsToolDefinition(): JsonObject = buildJsonObject {
        put("name", DIAGNOSTICS_TOOL_NAME)
        put("description", "Read bounded redacted browser-read diagnostics. No page text or screenshots. Read-only.")
        put("inputSchema", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
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

        if (operationTools != null && name in McpOperationContract.names) {
            if (params["arguments"] != null && params["arguments"] !is JsonObject) return jsonError(id, -32602, "Arguments must be object")
            val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
            val normalized = try { McpOperationContract.arguments(name, arguments) }
                catch (_: Exception) { return jsonError(id, -32602, "Invalid operation tool arguments") }
            return toolResult(id, operationTools.call(name, normalized))
        }

        return when (name) {
            STATUS_TOOL_NAME -> toolResult(id, buildJsonObject {
                statusTool.snapshot().forEach { (key, value) -> put(key, value) }
                put("mcp_tool_count", toolDefinitions().size)
                put("runtime_info_tool", if (runtimeInfoEnabled) RUNTIME_INFO_TOOL_NAME else "unavailable")
                put("tool_list_refresh_verified", false)
            })
            RUNTIME_INFO_TOOL_NAME -> if (runtimeInfoEnabled) {
                require(params["arguments"] == null || params["arguments"] is JsonObject)
                toolResult(id, runtimeInfo(params["arguments"] as? JsonObject ?: JsonObject(emptyMap())))
            } else jsonError(id, -32602, "Unknown tool: $name")
            DIAGNOSTICS_TOOL_NAME -> if (readAppTool != null) {
                val status = statusTool.snapshot()
                val diagnostics = JsonObject(BrowserReadDiagnostics.snapshot() + extraDiagnostics())
                toolResult(id, JsonObject(diagnostics + status.filterKeys { it == "version_name" || it == "version_code" }))
            } else jsonError(id, -32602, "Unknown tool: $name")
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
        // OCR text originates from captured pixels even when the image block is omitted.
        // Never downgrade revoked screenshot text into an unguarded text-only response.
        val screenshotDerived = result.structured["text_source"]?.jsonPrimitive?.content == "on_device_ocr" ||
            result.structured["capture_source"]?.jsonPrimitive?.content == "screenshot_ocr" ||
            result.structured["ocr_derived_content"]?.jsonPrimitive?.booleanOrNull == true
        if (screenshotDerived && (result.imageEpoch == null ||
                !ai.closepaw.security.ImageRevocationAuthority.isConsentStillValid(result.imageEpoch))) {
            return toolResult(id, buildJsonObject {
                put("status", "failed")
                put("reason_code", "image_consent_revoked")
                put("summary", "Screenshot-derived content was revoked.")
            }).copy(imageEpoch = result.imageEpoch)
        }
        val admittedImageEpoch = result.imageEpoch?.takeIf {
            ai.closepaw.security.ImageRevocationAuthority.isConsentStillValid(it)
        }
        val imageIncluded = admittedImageEpoch != null &&
            !result.imageBase64.isNullOrBlank() && !result.imageMimeType.isNullOrBlank()
        val structured = result.structured
        val summary = structured["summary"]?.jsonPrimitive?.content ?: "ClosePaw responded."
        val content = structured["content"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?: summary
        val isError = structured["status"]?.jsonPrimitive?.content == "failed"
        // Source provenance must survive revocation between the checks above.
        // The final MCP writer denies revoked epochs, including text-only OCR.
        return jsonResult(
            id,
            buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", content)
                    })
                    val imageData = result.imageBase64.takeIf { imageIncluded }
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
        ).copy(imageEpoch = if (screenshotDerived) result.imageEpoch else admittedImageEpoch.takeIf { imageIncluded })
    }

    private fun toolResult(id: JsonElement, structured: JsonObject): McpHttpResponse {
        // Bounded encoding rechecks live consent/session/source after conversion.
        // Metadata describes this response, never an earlier capture/ledger result.
        val imageEpoch = ai.closepaw.security.ImageRevocationAuthority.currentEpoch
        val image = if (ai.closepaw.security.ImageRevocationAuthority.isConsentStillValid(imageEpoch))
            ai.closepaw.chatgpt.control.VisualImageCache.encodedFor(structured)?.takeIf {
                ai.closepaw.security.ImageRevocationAuthority.isConsentStillValid(imageEpoch)
            }
        else null
        val delivered = withImageDeliveryMetadata(structured, image != null)
        val content = delivered["content"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?: delivered.toString()
        val isError = delivered["status"]?.jsonPrimitive?.content == "failed"
        return jsonResult(
            id,
            buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", content)
                    })
                    image?.let { image ->
                        add(buildJsonObject { put("type", "image"); put("mimeType", image.mimeType)
                            put("data", image.data) })
                    }
                })
                put("structuredContent", delivered)
                put("isError", isError)
            }
        ).copy(imageEpoch = imageEpoch.takeIf { image != null })
    }

    private fun withImageDeliveryMetadata(value: JsonObject, included: Boolean): JsonObject {
        fun mark(data: JsonObject): JsonObject = if (data["visual"] is JsonObject)
            JsonObject(data + ("image_included" to kotlinx.serialization.json.JsonPrimitive(included))) else data
        // Keep the same supported query/task envelope selection as imageFor.
        val result = value["result"] as? JsonObject
        val nestedData = result?.get("data")
        val data = (nestedData ?: value["data"]) as? JsonObject
        if (data?.get("visual") is JsonObject) {
            if (nestedData != null) return JsonObject(value + ("result" to JsonObject(result + ("data" to mark(data)))))
            return JsonObject(value + ("data" to mark(data)))
        }
        return mark(value)
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
        const val RUNTIME_INFO_TOOL_NAME = "get_runtime_info"
        const val STATUS_TOOL_NAME = "get_status"
        const val READ_APP_TOOL_NAME = "read_app"
        const val DIAGNOSTICS_TOOL_NAME = "get_diagnostics"
        const val DEFAULT_PROTOCOL_VERSION = "2025-06-18"
        private val SUPPORTED_PROTOCOL_VERSIONS = setOf("2024-11-05", "2025-03-26", DEFAULT_PROTOCOL_VERSION)
    }
}
