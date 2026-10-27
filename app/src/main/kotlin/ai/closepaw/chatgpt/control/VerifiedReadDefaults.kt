package ai.closepaw.chatgpt.control

import ai.closepaw.protocol.AppTier

/** OS-selected defaults establish an owner, never general permission to interact.
 * Explicit classifications and runtime restrictions remain authoritative. */
internal object VerifiedReadDefaults {
    fun permits(operation: String, explicitActiveSession: Boolean, bundled: AppTier?, userTier: AppTier?,
        selectedEnabledIme: Boolean, selectedBrowser: Boolean, generalWebHandler: Boolean): Boolean =
        operation == "read" && explicitActiveSession && bundled == null && userTier == null &&
            (selectedEnabledIme || (selectedBrowser && generalWebHandler))
}
