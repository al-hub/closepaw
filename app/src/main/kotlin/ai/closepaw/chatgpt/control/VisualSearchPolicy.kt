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
            url.rawPath in setOf("", "/", "/webhp") && url.rawFragment == null
    }

    fun refusal(frame: ControlFrame, scope: String, input: JsonObject?): String? {
        if (input?.get("submit_authorized")?.jsonPrimitive?.booleanOrNull != true)
            return "submission_authorization_required"
        if (scope != "keyboard" || frame.truncated || frame.navigationOnly || frame.reason != null || frame.packageName != chrome)
            return "keyboard_search_target_unverified"
        val field = frame.inputTarget ?: return "keyboard_search_target_unverified"
        val editor = frame.nodes.filter { it.sourcePackage == chrome && it.sourceWindowId == frame.windowId &&
            it.focused && it.focusable && it.editable && it.enabled && !it.password }.singleOrNull()
        val address = frame.nodes.filter { it.sourcePackage == chrome && it.sourceWindowId == frame.windowId &&
            it.resourceId == addressId && it.webEditorContainer < 0 && !it.focused && !it.password }.singleOrNull()
        if (field.id != editor?.id || field.webEditorContainer < 0 || field.resourceId == addressId ||
            address?.text?.let(::googleForm) != true)
            return "keyboard_search_target_unverified"
        val query = (input?.get("expected_search_query") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (query == null || query.length !in 1..128 || query.isBlank() ||
            query != query.trim() || query.any { it.isISOControl() } || InputPayloadPolicy.sensitive(query) ||
            field.text != query || field.selectionStart != query.length || field.selectionEnd != query.length)
            "keyboard_search_query_unverified" else null
    }
}

/** In the serialized UI executor, reserve before native dispatch. Cancellation
 * or an exception is not proof of no effect. Do not persist query text. */
internal class VisualSearchOnce {
    private var session: String? = null
    fun reserve(id: String): Boolean {
        if (session == id) return false
        session = id
        return true
    }
    fun releaseIfNoEffect(id: String, attempted: Boolean) {
        if (!attempted && session == id) session = null
    }
}
