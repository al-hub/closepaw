package ai.closepaw.platform.virtualdisplay

/** Per-projection identity, scoped to the OS consent round that created it. */
internal class MediaProjectionSessionOwner(private val gate: MediaProjectionConsentGate) {
    private val generation = gate.generation

    /**
     * Check identity and generation at execution time, including for queued teardown.
     * Hold the gate monitor through revocation so a new consent round cannot slip
     * between the generation check and the shared image-authority/cache barrier.
     * Service ownership and resource mutations run on the main looper.
     */
    fun runIfCurrent(currentOwner: MediaProjectionSessionOwner?, action: () -> Unit): Boolean =
        synchronized(gate) {
            if (currentOwner !== this || gate.generation != generation) return@synchronized false
            action()
            true
        }

    fun stopSharingIfCurrent(
        currentOwner: MediaProjectionSessionOwner?, activeRound: String?, requestedRound: String?,
        stop: () -> Unit,
    ): Boolean = activeRound != null && activeRound == requestedRound && runIfCurrent(currentOwner, stop)
}
