package ai.closepaw.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Explicitly authorized clipboard staging primitive. Never reads the existing
 * clipboard, never logs the payload and never attempts blind restoration.
 * A caller must revalidate device/session/foreground/field guards and obtain
 * per-operation clipboard approval before invoking this primitive.
 *
 * This is deliberately NOT an exposed remote operation or automatic fallback.
 */
internal class GuardedClipboardWriter(
    private val clipboard: ClipboardManager
) {
    enum class Outcome { STAGED_UNVERIFIED, DENIED, UNKNOWN }

    fun stage(text: String, consent: Boolean, safeTarget: Boolean): Outcome {
        if (!consent || !safeTarget || text.isEmpty()) return Outcome.DENIED
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("", text))
            // Clipboard write is only a local dispatch; no proof of Paste.
            Outcome.STAGED_UNVERIFIED
        } catch (_: RuntimeException) {
            Outcome.UNKNOWN
        }
    }

    companion object {
        fun from(context: Context): GuardedClipboardWriter? {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            return manager?.let(::GuardedClipboardWriter)
        }
    }
}
