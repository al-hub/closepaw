package ai.closepaw.chatgpt

/** Package gate for the single-screen read; hosted Gemini needs positive UI identity. */
internal object ReadAppForegroundPolicy {
    fun accepts(target: BrowserReadTarget, observedPackage: String?, verifiedGeminiHost: Boolean): Boolean =
        observedPackage == target.packageName
}
