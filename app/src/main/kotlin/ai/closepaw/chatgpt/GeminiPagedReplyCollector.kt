package ai.closepaw.chatgpt

/** Forward paging requires textual overlap. A successful scroll action alone never proves coverage. */
internal class GeminiPagedReplyCollector(
    private val ui: GeminiRoundTripUi,
    private val allowed: () -> Boolean,
    private val clockMillis: () -> Long,
    private val pause: suspend (Long) -> Unit,
    private val timeoutMs: Long,
    private val pollMs: Long,
    private val stableMs: Long,
    private val maxChars: Int,
    private val maxScrolls: Int = 24,
) {
    suspend fun collect(question: String, baseline: GeminiUiSnapshot, initial: GeminiUiSnapshot,
                        isCancelled: () -> Boolean): GeminiReplyCollection {
        val start = clockMillis(); var frame = initial
        var text = ""; var anchored = false; var submission = false
        var container: String? = null; var scrolls = 0; var pages = 0
        var previousPage: String? = null; var stableSince = start
        var lastScrolledText: String? = null; var movingForward = false
        var unchanged = 0; var polls = 0; var lastAcceptedPage: String? = null
        fun result(reason: String, done: Boolean = false, cancelled: Boolean = false) = GeminiReplyCollection(
            if (done) "completed" else if (cancelled) "cancelled" else if (text.isNotBlank()) "partial" else "failed",
            reason, text.takeIf { it.isNotBlank() }, !done && text.isNotBlank(),
            if (done) "paged_start_overlap_end_and_stable" else null, submission,
            collectionMs = (clockMillis() - start).coerceAtLeast(0), pagesCollected = pages,
        )
        while (++polls <= 600 && (clockMillis() - start).coerceAtLeast(0) < timeoutMs) {
            if (isCancelled()) return result("observer_cancelled", cancelled = true)
            if (!allowed()) return result("gemini_question_disabled")
            if (ui.isLocked()) return result("device_locked")
            if (!frame.foreground || frame.targetKey != baseline.targetKey) return result("target_changed")
            if (!frame.traversalComplete) return result("reply_incomplete_traversal")
            if (frame.composerText.isNotBlank()) return result("input_changed")
            if (frame.submittedQuestion != null && frame.submittedQuestion != question &&
                frame.submittedQuestion != baseline.submittedQuestion) return result("submitted_question_changed")
            val page = frame.replyPage
            if (page == null) {
                // A submitted turn can temporarily replace its scroll tree before any body arrives.
                if (anchored || text.isNotBlank() || scrolls > 0) return result("reply_scroll_region_lost")
                pause(pollMs); frame = ui.snapshot(); continue
            }
            if (page.conflictingTurn) return result("submitted_question_changed")
            if (container != null && container != page.containerKey && (anchored || scrolls > 0)) return result("reply_scroll_region_changed")
            container = page.containerKey
            if (page.questionVisible) submission = true
            if (page.text != previousPage) { previousPage = page.text; stableSince = clockMillis(); unchanged = 0 }
            val stable = clockMillis() - stableSince >= stableMs
            if (frame.generating == true) {
                // Generation can still replace the body. Never join snapshots across that boundary.
                text = ""; anchored = false; lastScrolledText = null; pages = 0; lastAcceptedPage = null; stableSince = clockMillis()
            } else if (stable && (page.text.isNotBlank() || !page.questionVisible)) {
                if ((anchored || page.questionVisible) && page.textEvidence.any { it in baseline.observedTextEvidence })
                    return result("reply_prior_text_overlap")
                if (anchored && lastScrolledText != null && page.text == lastScrolledText && page.endVisible && text.isNotBlank())
                    return result("reply_completed", done = true)
                if (lastScrolledText != null && page.text == lastScrolledText) {
                    // Android may report success for an animation or no-op; allow bounded settling.
                    if (++unchanged >= 3) return result("reply_scroll_stalled")
                } else {
                    if (!anchored && page.questionVisible) {
                        anchored = true; text = page.text; pages = 1; lastAcceptedPage = page.text
                    } else if (anchored && movingForward) {
                        val joined = joinOverlapping(text, page.text) ?: return result("reply_scroll_gap")
                        text = joined
                        if (lastAcceptedPage != page.text) { pages++; lastAcceptedPage = page.text }
                    } else if (anchored) { text = page.text; lastAcceptedPage = page.text }
                    lastScrolledText = null
                    if (text.length > maxChars) { text = text.take(maxChars); return result("reply_length_limit") }
                    if (anchored && page.endVisible && page.text.isNotBlank()) return result("reply_completed", done = true)
                    val forward = anchored
                    if (!(if (forward) page.canForward else page.canBackward)) {
                        if (!anchored) return result("reply_start_unconfirmed")
                        // No forward action is not an end marker: response/layout/footer can still arrive.
                        pause(pollMs); frame = ui.snapshot(); continue
                    }
                    if (scrolls >= maxScrolls) return result("reply_scroll_limit")
                    if (isCancelled()) return result("observer_cancelled", cancelled = true)
                    if (!allowed()) return result("gemini_question_disabled")
                    if (ui.isLocked()) return result("device_locked")
                    if (!ui.scrollReply(baseline.targetKey, page.containerKey, forward)) return result("reply_scroll_unavailable")
                    scrolls++; movingForward = forward; lastScrolledText = page.text; stableSince = clockMillis()
                }
            }
            pause(pollMs)
            frame = ui.snapshot()
        }
        return result("reply_timeout")
    }

    companion object {
        fun joinOverlapping(collected: String, next: String): String? {
            if (collected.isBlank() || next.isBlank()) return null
            if (collected.endsWith(next)) return collected
            // Exact paragraph or character overlap preserves repeated prose; never global-deduplicate.
            for (n in minOf(collected.length, next.length) downTo 1) {
                if (collected.regionMatches(collected.length - n, next, 0, n) &&
                    (n >= 16 || (collected.length == n && next.length == n) ||
                        (collected.getOrNull(collected.length - n - 1) == '\n' && next.getOrNull(n) == '\n'))) {
                    return collected + next.substring(n)
                }
            }
            return null
        }
    }
}
