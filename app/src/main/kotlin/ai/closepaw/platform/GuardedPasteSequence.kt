package ai.closepaw.platform

import kotlinx.coroutines.CancellationException

/**
 * Guarded one-shot clipboard/Paste sequence. The caller owns app/session/window
 * inspection and supplies a fresh guard for each irreversible boundary.
 * Neither a clipboard write nor a Paste dispatch is evidence of inserted text.
 *
 * Kept internal until the remote action policy, target identity and post-effect
 * verification are connected. No automatic fallback or retry is performed.
 */
internal class GuardedPasteSequence(
    private val stage: (String) -> GuardedClipboardWriter.Outcome,
    private val paste: suspend () -> Boolean
) {
    enum class Outcome { DENIED, STAGE_UNKNOWN, STOPPED_AFTER_STAGING, PASTE_UNKNOWN }

    suspend fun execute(
        text: String,
        beforeStage: () -> Boolean,
        beforePaste: () -> Boolean,
        preflightTarget: suspend () -> Boolean = { true }
    ): Outcome {
        if (text.isEmpty() || !beforeStage()) return Outcome.DENIED
        if (!preflightTarget() || !beforeStage()) return Outcome.DENIED
        // Runtime failure from the platform may occur after staging.
        // Treat it as unknown and never attempt Paste or automatic recovery.
        val stageOutcome = try { stage(text) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { return Outcome.STAGE_UNKNOWN }
        when (stageOutcome) {
            GuardedClipboardWriter.Outcome.DENIED -> return Outcome.DENIED
            GuardedClipboardWriter.Outcome.UNKNOWN -> return Outcome.STAGE_UNKNOWN
            GuardedClipboardWriter.Outcome.STAGED_UNVERIFIED -> Unit
        }
        // The application, focused field, permission or user X may have changed
        // while the clipboard was being staged. Never Paste based on stale consent.
        if (!beforePaste()) return Outcome.STOPPED_AFTER_STAGING
        // A false return can be a post-dispatch failure. Do not replay.
        try { paste() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Post-dispatch status is unknown. */ }
        return Outcome.PASTE_UNKNOWN
    }
}
