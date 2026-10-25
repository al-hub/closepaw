package ai.closepaw.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import java.util.UUID

/** Holds clipboard data only for this operation, never logs or persists it.
 * Android null/false is ambiguous: refuse without writing. This deliberately
 * supports only a readable single plain-text clip. Denial leaves that clip untouched;
 * successful staging requires explicit authorization to replace it.
 * Every Paste requires re-reading our exact unpredictable marker AND payload.
 * Restore is conditional; unavailable access or competing content means no write.
 * Android has no clipboard CAS: conditional restoration is disabled to avoid a
 * read/write race overwriting another app's clipboard. The authorized staged clip
 * is left in place and the result explicitly reports that policy. */
internal class OwnedClipboardLease private constructor(
    private val clipboard: ClipboardManager,
    private val text: String
) {
    private val marker = UUID.randomUUID().toString()
    private var staged = false
    fun stage(): Boolean {
        val clip = ClipData.newPlainText("", text)
        clip.description.extras = PersistableBundle().apply {
            putString(MARKER, marker)
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        staged = true // setPrimaryClip can throw after altering external state.
        clipboard.setPrimaryClip(clip)
        return ownsCurrent()
    }
    fun ownsCurrent(): Boolean {
        if (!staged) return false
        val clip = clipboard.primaryClip ?: return false
        return clip.itemCount == 1 && clip.description.extras?.getString(MARKER) == marker &&
            clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            clip.getItemAt(0).uri == null && clip.getItemAt(0).intent == null &&
            clip.getItemAt(0).text?.toString() == text
    }
    companion object {
        private const val MARKER = "ai.closepaw.input.lease"
        data class Acquisition(val lease: OwnedClipboardLease? = null, val refusal: String? = null)
        /** Explicit replacement consent is independent of general UI consent.
         * Null is never classified as empty: Android deliberately returns it for denied reads. */
        fun inspectAndAcquire(clipboard: ClipboardManager, text: String, replacementAuthorized: Boolean): Acquisition {
            if (!replacementAuthorized || text.isEmpty()) return Acquisition(refusal = "clipboard_replacement_authorization_required")
            val old = try { clipboard.primaryClip }
                catch (_: SecurityException) { return Acquisition(refusal = "clipboard_read_permission_denied") }
                catch (_: RuntimeException) { return Acquisition(refusal = "clipboard_read_unavailable") }
                ?: return Acquisition(refusal = "clipboard_empty_or_read_restricted")
            if (old.itemCount != 1 || !old.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                old.getItemAt(0).uri != null || old.getItemAt(0).intent != null || old.getItemAt(0).text == null)
                return Acquisition(refusal = "clipboard_existing_content_unsupported")
            return Acquisition(OwnedClipboardLease(clipboard, text))
        }
        fun acquire(clipboard: ClipboardManager, text: String, replacementAuthorized: Boolean): OwnedClipboardLease? =
            inspectAndAcquire(clipboard, text, replacementAuthorized).lease
    }
}
