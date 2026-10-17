package ai.closepaw.chatgpt

/** Transient accessibility observations. Never put question/reply text in diagnostics. */
internal data class GeminiReply(val id: String, val text: String, val complete: Boolean = false)
internal data class GeminiUiSnapshot(
    val foreground: Boolean,
    val targetKey: String,
    val composerCandidates: Int = 0,
    val composerText: String = "",
    val sendCandidates: Int = 0,
    val sendEnabled: Boolean = false,
    val generating: Boolean? = null,
    val submittedQuestion: String? = null,
    val replies: List<GeminiReply> = emptyList(),
    val traversalComplete: Boolean = true,
)
internal enum class GeminiSendResult { CLICKED, TARGET_CHANGED, INPUT_CHANGED, REJECTED }
internal interface GeminiRoundTripUi : GeminiDraftUi {
    suspend fun snapshot(): GeminiUiSnapshot
    /** Reacquire and validate target, consent, lock, exact composer and unique semantic send. */
    suspend fun sendIfMatches(text: String, targetKey: String): GeminiSendResult
}
