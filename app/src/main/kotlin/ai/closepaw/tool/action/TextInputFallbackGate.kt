package ai.closepaw.tool.action

/**
 * Fail-closed transition gate for text input channels.
 *
 * A failed platform call does not necessarily mean that no text was inserted.
 * Only an explicit, pre-dispatch unsupported-target result permits another channel.
 * Timeout, post-dispatch failure and ambiguous results require a fresh observation;
 * they MUST NOT be replayed automatically.
 */
internal object TextInputFallbackGate {
    enum class Decision { NEXT_CHANNEL, STOP_UNKNOWN }

    fun afterDirectSetFailure(reason: String?): Decision = when (reason?.trim()?.lowercase()) {
        "no node", "no node at point", "no editable node", "no focused editable",
        "no focused editable element", "unsupported action_set_text" -> Decision.NEXT_CHANNEL
        else -> Decision.STOP_UNKNOWN
    }
}
