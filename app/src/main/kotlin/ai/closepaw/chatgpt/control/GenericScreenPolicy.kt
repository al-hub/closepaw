package ai.closepaw.chatgpt.control

/** Immutable policy projection: a verified host exception can never soften the BLOCKED floor. */
internal object GenericScreenPolicy {
    fun refusal(blocked: Boolean, sensitive: Boolean, packageAllowed: Boolean,
        isGeminiHost: Boolean, verifiedGemini: Boolean, readable: Boolean): String? = when {
        blocked -> "blocked_app"
        sensitive -> "authentication_boundary"
        (isGeminiHost && !verifiedGemini) || (!packageAllowed && !(isGeminiHost && verifiedGemini)) -> "blocked_or_cautious_app"
        !readable -> "screen_unreadable"
        else -> null
    }
}
