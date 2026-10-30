package ai.closepaw.platform.virtualdisplay

/**
 * Lifecycle/race/freshness policy for the MediaProjection mirror scope
 * of issue #165. Pure Kotlin (no Android types), so the ordering guarantees can be
 * proven by JVM unit tests.
 *
 * Rules:
 * - Fail closed by construction: a surface can be opened once per session, and
 *   [invalidate] (revocation/teardown) permanently arms it; after that no path —
 *   including a racing concurrent [openSurface] — can make acquisition legal again.
 * - ProjectionImageLease may acquire bounded fresh pixels while this policy is open;
 *   transport still requires isolated accessibility ownership verification and masking.
 */
class MediaProjectionFramePolicy(
    private val maxFrameAgeMs: Long = 1_000L,
    private val maxMirrorPixels: Long = 8_000_000L,
) {
    private val lock = Any()
    private var opened = false
    private var invalid = false

    /** Open the single bounded capture surface; false if closed,already open or invalidated. */
    fun openSurface(): Boolean = synchronized(lock) {
        if (invalid || opened) false else { opened = true; true }
    }

    /** Recheck immediately before every acquire; revocation wins the race. */
    fun acquireAllowed(): Boolean = synchronized(lock) { opened && !invalid }

    /**
     * Mark the session revoked/ending (projection onStop, service teardown). Never
     * resurrected by [closeSurface] or a later [openSurface]; the only route back is a
     * fresh OS consent round producing a new policy.
     */
    fun invalidate(): Boolean = synchronized(lock) {
        val firstTime = !invalid
        invalid = true
        opened = false
        firstTime
    }

    /** Release surface state after teardown. Idempotent; cannot undo [invalidate]. */
    fun closeSurface(): Boolean = synchronized(lock) {
        val openWas = opened
        opened = false
        invalid = true // one OS consent permits one bounded surface only
        openWas
    }

    /** Mirrored frames above this pixel budget are refused (same cap as window screenshots). */
    fun acceptsMirrorGeometry(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height <= maxMirrorPixels

    /** Transport expiration uses the same monotonic clock, with time for isolated verification. */
    fun isDeliveryFresh(capturedAtMs: Long, nowMs: Long): Boolean =
        capturedAtMs >= 0 && nowMs >= capturedAtMs && nowMs - capturedAtMs <= 10_000L

    /** A frame older than the bound, or with an unknown clock value, is never fresh. */
    fun isFrameFresh(capturedAtMs: Long, nowMs: Long): Boolean =
        capturedAtMs >= 0 && nowMs >= capturedAtMs && nowMs - capturedAtMs <= maxFrameAgeMs
}
