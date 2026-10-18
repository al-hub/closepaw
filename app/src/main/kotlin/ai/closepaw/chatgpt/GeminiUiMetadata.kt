package ai.closepaw.chatgpt

import kotlinx.serialization.json.*

/** Privacy boundary: raw node text, labels, target keys and reply IDs never cross this projection. */
internal object GeminiUiMetadata {
    private const val MAX_NODES = 160
    private const val MAX_NODE_JSON = 26000
    private val targetPackages = setOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox")
    private val packages = targetPackages + "android"
    private val resourceName = Regex("[a-z][a-z0-9_]{0,79}")
    private val bareIds = setOf("assistant_robin_send_button", "robin_send_button", "send_button",
        "assistant_robin_stop_button", "stop_generating_button", "assistant_robin_copy_button", "copy_response_button",
        "assistant_robin_input_text", "robin_input_text", "prompt_input", "assistant_robin_response_text",
        "robin_response_text", "assistant_robin_response_container", "assistant_robin_query_text", "robin_query_text", "user_query")
    private val classNames = setOf("android.view.View", "android.view.ViewGroup", "android.view.SurfaceView", "android.webkit.WebView",
        "android.widget.Button", "android.widget.ImageButton", "android.widget.TextView", "android.widget.EditText",
        "android.widget.ImageView", "android.widget.FrameLayout", "android.widget.LinearLayout", "android.widget.RelativeLayout",
        "android.widget.ScrollView", "android.widget.HorizontalScrollView", "android.widget.ListView", "android.widget.ProgressBar",
        "androidx.compose.ui.platform.ComposeView", "androidx.compose.ui.platform.AndroidComposeView",
        "androidx.recyclerview.widget.RecyclerView", "androidx.core.widget.NestedScrollView",
        "androidx.constraintlayout.widget.ConstraintLayout", "com.google.android.material.button.MaterialButton")
    private val windowReasons = setOf("not_observed", "active_root", "active_root_missing", "foreground_application",
        "wrong_foreground", "traversal_incomplete", "google_host_unverified", "composer_missing", "composer_ambiguous", "gemini_composer")

    fun build(nodes: List<GeminiUiNode>, window: DraftWindowEvidence, snapshot: GeminiUiSnapshot): JsonObject {
        val rootPackage = window.rootPackage ?: nodes.firstOrNull()?.packageName
        val verified = snapshot.foreground && rootPackage in targetPackages
        val complete = snapshot.traversalComplete
        val reason = when {
            !verified -> "target_not_verified"
            !complete -> "traversal_incomplete"
            snapshot.composerCandidates == 0 -> "composer_missing"
            snapshot.composerCandidates != 1 -> "composer_ambiguous"
            snapshot.sendCandidates == 0 -> "send_control_missing"
            snapshot.sendCandidates != 1 -> "send_control_ambiguous"
            !snapshot.sendEnabled -> "send_control_disabled"
            else -> "controls_observed"
        }
        var used = 0
        val exported = mutableListOf<JsonElement>()
        if (verified) for (node in nodes) {
            if (exported.size >= MAX_NODES) break
            // Untrusted app nodes do not contribute even lengths or inferred roles.
            if (node.packageName !in packages) continue
            val entry = buildJsonObject {
                put("index", node.index); put("parent", node.parent)
                safeResourceId(node.resourceId)?.let { put("resource_id", it) }
                node.className.takeIf { it in classNames }?.let { put("class_name", it) }
                put("visible",node.visible); put("enabled",node.enabled); put("editable",node.editable)
                put("clickable",node.clickable); put("click_action",node.clickAction)
                put("scrollable",node.scrollable); put("scroll_forward",node.scrollForward); put("scroll_backward",node.scrollBackward)
                put("text_length",node.text.length); put("description_length",node.description.length)
                put("role", when {
                    node.composer -> "composer"
                    GeminiUiControlResolver.isSendRole(node) -> "send"
                    GeminiUiControlResolver.isStopRole(node) -> "stop"
                    GeminiUiControlResolver.isCopyRole(node) -> "copy"
                    GeminiUiControlResolver.isFeedbackRole(node) -> "feedback"
                    else -> "unclassified"
                })
            }
            val cost = entry.toString().length + 1
            if (used + cost > MAX_NODE_JSON) break
            exported.add(entry); used += cost
        }
        return buildJsonObject {
            put("reply_page_available", verified && snapshot.replyPage != null)
            snapshot.replyPage?.takeIf { verified }?.let { page ->
                put("page_start_visible", page.questionVisible); put("page_end_visible", page.endVisible)
                put("page_can_forward", page.canForward); put("page_can_backward", page.canBackward)
            }
            put("foreground",verified); put("reason_code",reason)
            put("window",buildJsonObject {
                window.observedPackage?.takeIf { it in packages }?.let { put("observed_package",it) }
                rootPackage?.takeIf { it in packages }?.let { put("root_package",it) }
                window.windowId?.let { put("window_id",it) }; window.windowType?.let { put("window_type",it) }
                window.active?.let { put("active",it) }; window.focused?.let { put("focused",it) }
                put("window_reason",window.reason.takeIf { it in windowReasons } ?: "unclassified")
            })
            put("node_count",if(verified) nodes.size else 0); put("nodes_exported",exported.size)
            put("nodes_truncated",verified && exported.size < nodes.size); put("traversal_complete",complete && verified)
            put("composer_candidates",if(verified) snapshot.composerCandidates.coerceAtLeast(0) else 0)
            put("send_candidates",if(verified) snapshot.sendCandidates.coerceAtLeast(0) else 0)
            put("send_enabled",verified && snapshot.sendEnabled)
            put("generating",if(verified) snapshot.generating?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
            put("reply_count",if(verified) snapshot.replies.size else 0)
            put("nodes",JsonArray(exported))
        }
    }
    private fun safeResourceId(value: String): String? {
        if (value in bareIds) return value
        val parts = value.split(":id/", limit=2)
        return value.takeIf { parts.size == 2 && parts[0] in packages && resourceName.matches(parts[1]) }
    }
}
