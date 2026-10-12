package ai.closepaw.chatgpt

/** Configured read targets; this does not assert installation or permission readiness. */
internal data class BrowserReadTarget(
    val id: String,
    val packageName: String,
    val displayName: String,
    val aliases: Set<String> = emptySet(),
) {
    companion object {
        val supported: List<BrowserReadTarget> = listOf(
            BrowserReadTarget("samsung_internet", "com.sec.android.app.sbrowser", "Samsung Internet",
                setOf("samsung_browser", "samsunginternet")),
            BrowserReadTarget("chrome", "com.android.chrome", "Chrome", setOf("google_chrome")),
            BrowserReadTarget("gemini", "com.google.android.apps.bard", "Gemini", setOf("google_gemini")),
        )

        fun from(raw: String): BrowserReadTarget? {
            val normalized = raw.trim().lowercase().replace(' ', '_').replace('-', '_')
            return supported.firstOrNull { it.id == normalized || normalized in it.aliases }
        }
    }
}
