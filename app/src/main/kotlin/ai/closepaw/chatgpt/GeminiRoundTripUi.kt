package ai.closepaw.chatgpt

/** Transient accessibility observations. Never put question/reply text in diagnostics. */
internal data class GeminiReply(val id: String, val text: String, val complete: Boolean = false, val textEvidence: Set<String> = emptySet())
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
    val replyCoverageComplete: Boolean = true,
    val replyPage: GeminiReplyPage? = null,
    val observedTextEvidence: Set<String> = emptySet(),
    val observationReason: String = "unclassified",
)
internal enum class GeminiSendResult { CLICKED, TARGET_CHANGED, INPUT_CHANGED, REJECTED }
internal interface GeminiRoundTripUi : GeminiDraftUi {
    suspend fun snapshot(): GeminiUiSnapshot
    /** Only the validated conversation scroll container; never coordinates or composer gestures. */
    suspend fun scrollReply(targetKey: String, containerKey: String, forward: Boolean): Boolean = false
    /** Reacquire and validate target, consent, lock, exact composer and unique semantic send. */
    suspend fun sendIfMatches(text: String, targetKey: String): GeminiSendResult
}
