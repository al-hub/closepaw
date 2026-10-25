package ai.closepaw.tool.action

/**
 * Policy-only planner. Does not execute an Android action. A channel is eligible
 * only with fresh, explicit support evidence for this target and observation.
 * Submission/Enter is deliberately outside this planner.
 */
internal object UnifiedTextInputPolicy {
    enum class Channel { DIRECT, CLIPBOARD_PASTE, VISIBLE_IME }
    enum class Result { NOT_ATTEMPTED, DEFINITELY_UNSUPPORTED, VERIFIED, UNKNOWN, PARTIAL, CANCELLED }

    data class Evidence(
        val fresh: Boolean,
        val allowed: Boolean,
        val targetVerified: Boolean,
        val directSupported: Boolean,
        val clipboardAllowed: Boolean,
        val pasteSupported: Boolean,
        val imeKeysVerified: Boolean,
        val sensitive: Boolean,
        val sessionActive: Boolean,
    )

    fun first(e: Evidence): Channel? {
        if (!e.fresh || !e.allowed || !e.targetVerified || e.sensitive || !e.sessionActive) return null
        return when {
            e.directSupported -> Channel.DIRECT
            e.clipboardAllowed && e.pasteSupported -> Channel.CLIPBOARD_PASTE
            e.imeKeysVerified -> Channel.VISIBLE_IME
            else -> null
        }
    }

    fun next(previous: Channel, result: Result, e: Evidence): Channel? {
        if (result != Result.DEFINITELY_UNSUPPORTED) return null
        if (!e.fresh || !e.allowed || !e.targetVerified || e.sensitive || !e.sessionActive) return null
        return when (previous) {
            Channel.DIRECT -> when {
                e.clipboardAllowed && e.pasteSupported -> Channel.CLIPBOARD_PASTE
                e.imeKeysVerified -> Channel.VISIBLE_IME
                else -> null
            }
            Channel.CLIPBOARD_PASTE -> if (e.imeKeysVerified) Channel.VISIBLE_IME else null
            Channel.VISIBLE_IME -> null
        }
    }
}
