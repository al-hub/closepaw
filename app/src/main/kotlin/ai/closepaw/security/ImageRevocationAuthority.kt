package ai.closepaw.security

/**
 * Synchronous, process-local image consent authority. This complements OS consent; it
 * cannot obtain a projection or bypass Android permissions. Each grant/revoke advances
 * the epoch, including repeated revocations. Epochs are never reset or reused.
 *
 * Consumers must validate their captured epoch again before delivering image data.
 * This authority alone does not gate MCP responses or clear image caches.
 */
object ImageRevocationAuthority {
    private var epoch = 0L
    private var granted = false

    val currentEpoch: Long
        @Synchronized get() = epoch

    val isConsentGranted: Boolean
        @Synchronized get() = granted

    /** Call only after obtaining a real OS projection and consuming its consent token. */
    @Synchronized
    internal fun grantConsent(): Long = advanceEpoch(true)

    /** Prevent a late projection setup from overwriting a concurrent revocation. */
    @Synchronized
    internal fun grantConsentIfCurrent(expectedEpoch: Long): Boolean {
        if (epoch != expectedEpoch) return false
        advanceEpoch(true)
        return true
    }

    @Synchronized
    fun revokeConsent(): Long = advanceEpoch(false)

    @Synchronized
    fun isConsentStillValid(epoch: Long): Boolean = granted && this.epoch == epoch

    fun getCurrentEpochValue(): Long = currentEpoch

    // Called under this object's monitor. Exhaustion fails closed rather than wrapping.
    private fun advanceEpoch(consent: Boolean): Long {
        granted = false
        check(epoch < Long.MAX_VALUE) { "Image consent epoch exhausted" }
        epoch += 1
        granted = consent
        return epoch
    }
}
