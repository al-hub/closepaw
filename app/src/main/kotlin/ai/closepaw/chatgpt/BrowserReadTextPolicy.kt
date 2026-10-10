package ai.closepaw.chatgpt

/**
 * Pure filtering policy for browser Accessibility text.
 *
 * A browser can expose editable password fields in the Accessibility tree.
 * Never include those fields (including descriptions or hints) in read_app.
 * Hidden/offscreen nodes and Android password placeholders are also excluded.
 */
internal object BrowserReadTextPolicy {
    fun visibleText(
        visible: Boolean,
        password: Boolean,
        text: String?,
        description: String?,
        hint: String?,
    ): List<String> {
        if (!visible || password) return emptyList()
        return sequenceOf(text, description, hint)
            .filterNotNull()
            .map(String::trim)
            .filter { it.isNotBlank() && it != "[password]" }
            .toList()
    }
}
