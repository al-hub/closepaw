package ai.closepaw.chatgpt

/** Invocation-local immutable projection. Text is never diagnostic metadata. */
internal data class GeminiUiNode(
    val index: Int,
    val parent: Int = -1,
    val packageName: String = "",
    val resourceId: String = "",
    val className: String = "",
    val text: String = "",
    val description: String = "",
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val editable: Boolean = false,
    val password: Boolean = false,
    val clickable: Boolean = false,
    val clickAction: Boolean = false,
    val composer: Boolean = false,
    val showingHint: Boolean = false,
)

/** Invocation-local fingerprints only; never serialized into diagnostics. */
internal object GeminiUiTextEvidence {
    fun hash(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
