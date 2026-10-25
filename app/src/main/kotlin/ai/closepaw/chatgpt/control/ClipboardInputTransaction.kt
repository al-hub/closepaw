package ai.closepaw.chatgpt.control

import kotlinx.coroutines.CancellationException

/** The native adapter must establish readable clipboard provenance before staging.
 * Null/false from Android never establishes an empty clipboard. No blind restore.
 * The UI observation is consumed even when only the clipboard was changed. */
internal class ClipboardInputTransaction(
    private val stage: () -> Boolean,
    private val pasteWithFreshGuards: suspend () -> ControlEffect,
    private val restoreIfOwned: () -> Unit
) {
    suspend fun execute(): ControlEffect {
        var stagingStarted = false
        return try {
            stagingStarted = true
            if (!stage()) ControlEffect(true, false, "clipboard_stage_unknown")
            else {
                val pasted = pasteWithFreshGuards()
                // Staging is already an external side effect, even if Paste was refused.
                pasted.copy(attempted = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ControlEffect(stagingStarted, false, "clipboard_effect_unknown")
        } finally {
            if (stagingStarted) {
                try { restoreIfOwned() } catch (_: Exception) { /* Never retry or overwrite blindly. */ }
            }
        }
    }
}
