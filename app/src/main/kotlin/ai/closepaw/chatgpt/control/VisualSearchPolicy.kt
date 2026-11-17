package ai.closepaw.chatgpt.control

import java.net.URI
import kotlinx.serialization.json.*

/** One explicitly authorized Chrome Google web-form submission, not a general
 * visual Enter/send/go action. Never infer search from a submit glyph. */
internal object VisualSearchPolicy {
    private const val chrome = "com.android.chrome"
    private const val addressId = "$chrome:id/url_bar"

    fun role(text: String, description: String, className: String, childCount: Int): KeyboardKeyPolicy.Key? =
        KeyboardKeyPolicy.identifyLabel(text, description)?.takeIf {
            it.kind == KeyboardKeyPolicy.Kind.SUBMIT && it.label.lowercase() in setOf("search", "검색") &&
                childCount in 0..1 && className in setOf("android.widget.Button", "android.view.View", "android.view.ViewGroup")
        }

    fun isSearch(key: KeyboardKeyPolicy.Key?) = key?.kind == KeyboardKeyPolicy.Kind.SUBMIT &&
        key.label.lowercase() in setOf("search", "검색")

    private fun googleForm(text: String): Boolean {
        val url = runCatching {
            URI(if (text.contains("://")) text else "https://$text")
        }.getOrNull() ?: return false
        return url.scheme == "https" && url.rawUserInfo == null && url.port == -1 &&
            url.host in setOf("google.com", "www.google.com") &&
            url.rawPath in setOf("", "/", "/webhp") &&
            // Google mobile adds this exact same-document marker when the
            // web search field gets focus. Do not admit a non-empty pi
            // value, encoded variant, or arbitrary fragment parameters.
            (url.rawFragment == null || url.rawFragment == "sbfbu=1&pi=")
    }

    fun address(frame: ControlFrame): ControlNode? = frame.nodes.singleOrNull {
        it.sourcePackage == chrome && it.sourceWindowId == frame.windowId &&
            it.resourceId == addressId && it.webEditorContainer < 0 && !it.focused && !it.password
    }?.takeIf { googleForm(it.text) }

    private fun inActionRow(frame: ControlFrame, node: ControlNode): Boolean {
        val target = KeyboardImageFallbackPolicy.bounds(node.bounds) ?: return false
        val ime = frame.keyboard ?: return false
        val h = target.bottom - target.top
        val literal = frame.nodes.mapNotNull {
            val role = KeyboardKeyPolicy.identifyLabel(it.text, it.description)
            if (!it.enabled || it.password || it.sourceWindowId != ime.windowId || it.sourcePackage != ime.packageName ||
                (!it.visualKeyCandidate && !it.textEntryKey) ||
                role?.kind != KeyboardKeyPolicy.Kind.CHARACTER ||
                role.label.singleOrNull()?.isLetterOrDigit() != true)
                return@mapNotNull null
            KeyboardImageFallbackPolicy.bounds(it.bounds)
        }
        if (literal.isEmpty() || target.right < literal.maxOf { it.right } - h / 2 ||
            literal.any { it.center.second >= target.center.second }) return false
        val adjacent = literal.filter {
            it.center.first < target.left && target.top - it.bottom in 0..(h * 2) &&
                it.bottom - it.top in (h * 3 / 4)..(h * 5 / 4)
        }
        return adjacent.isNotEmpty() && adjacent.minOf { it.right } <= adjacent.maxOf { it.left }
    }

    fun refusal(frame: ControlFrame, node: ControlNode, scope: String, input: JsonObject?): String? {
        if (input?.get("submit_authorized")?.jsonPrimitive?.booleanOrNull != true)
            return "submission_authorization_required"
        if (scope != "keyboard" || frame.truncated || frame.navigationOnly || frame.reason != null || frame.packageName != chrome)
            return "keyboard_search_target_unverified"
        val field = frame.inputTarget ?: return "keyboard_search_target_unverified"
        val editor = frame.nodes.filter { it.sourcePackage == chrome && it.sourceWindowId == frame.windowId &&
            it.focused && it.focusable && it.editable && it.enabled && !it.password }.singleOrNull()
        if (field.id != editor?.id || field.webEditorContainer < 0 || field.resourceId == addressId ||
            address(frame) == null)
            return "keyboard_search_target_unverified"
        if (!inActionRow(frame, node)) return "keyboard_search_action_row_unverified"
        val query = (input?.get("expected_search_query") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (query == null || query.length !in 1..128 || query.isBlank() ||
            query != query.trim() || query.any { it.isISOControl() } || InputPayloadPolicy.sensitive(query) ||
            field.text != query || field.selectionStart != query.length || field.selectionEnd != query.length)
            "keyboard_search_query_unverified" else null
    }
}
