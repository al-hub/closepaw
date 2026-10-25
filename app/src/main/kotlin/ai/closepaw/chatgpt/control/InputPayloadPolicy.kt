package ai.closepaw.chatgpt.control

/** Conservative known-secret/identifier patterns, not a promise to classify arbitrary prose. */
internal object InputPayloadPolicy {
    private val patterns = listOf(
        Regex("(?i)(?:password|passwd|pwd|token|secret|api[_ -]?key|authorization|비밀번호|인증번호)\\s*[:=]"),
        Regex("(?i)\\b(?:bearer\\s+|sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9_]{12,}|github_pat_[A-Za-z0-9_]{12,})"),
        Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"),
        Regex("-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----"),
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        Regex("(?<![0-9])01[016789][- ]?[0-9]{3,4}[- ]?[0-9]{4}(?![0-9])"),
        Regex("(?<![0-9])[0-9]{6}[- ]?[1-4][0-9]{6}(?![0-9])")
    )
    fun sensitive(text: String): Boolean = patterns.any { it.containsMatchIn(text) }
}
