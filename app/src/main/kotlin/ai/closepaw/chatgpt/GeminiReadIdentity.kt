package ai.closepaw.chatgpt

/** Read identity only. Never grants a composer, write permission, or a model-selection success. */
internal object GeminiReadIdentity {
    fun nativeShell(packageName: String?, resourceIds: Set<String>, visibleResourceIds: Set<String> = resourceIds): Boolean {
        if (packageName != AndroidGeminiDraftUi.GOOGLE_HOST) return false
        val prefix = "$packageName:id/"
        return "${prefix}assistant_robin_main_activity" in visibleResourceIds &&
            listOf("assistant_robin_side_nav_host_content_container", "assistant_robin_chat_fragment_container")
                .any { "$prefix$it" in visibleResourceIds }
    }
}
