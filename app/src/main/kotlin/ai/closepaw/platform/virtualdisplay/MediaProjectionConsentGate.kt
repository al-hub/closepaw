package ai.closepaw.platform.virtualdisplay

/**
 * Minimal, fail-closed state/lifecycle tracker for the optional MediaProjection media-capture
 * consent of issue #165.
 *
 * Scope and safety rules:
 * - This class ONLY models consent state reported to it. It never launches, automates or
 *   acknowledges the OS consent dialog, and never touches MediaProjectionManager.
 * - It never caches the MediaProjection instance or any capture resource. The only value it
 *   returns is the opaque one-shot session id supplied by the caller at grant time, which acts
 *   as a staleness/generation guard, not as a usable handle.
 * - Consent is single-use: once consumed (projection taken into use by the caller), it is
 *   terminal and the grant must be renewed via a fresh OS consent round.
 * - Deny, cancel and revoke all fail closed: the gate can never be returned to an
 *   "implicitly granted" state; the only route back to a usable grant is a new OS decision.
 * - It exposes no screenshot, capture or coordinate capability whatsoever.
 *
 * Thread-safety: [state] reads/writes are atomic (`@Volatile`); event callbacks are expected
 * on the caller's lifecycle thread. Capture consumers must re-check [isProbeAllowed] after
 * any state read.
 */
sealed interface ProjectionConsentState {
    /** No consent round has started; capture is closed. */
    data object NotRequested : ProjectionConsentState

    /** The OS consent dialog has been surfaced by the caller (unaudited, never automated). */
    data object Requesting : ProjectionConsentState

    /**
     * The OS reported the user granted consent. Carries the one-shot session id supplied by
     * the caller. Capture is open ONLY for the holder of this exact id, once.
     */
    data class Granted(val sessionId: String) : ProjectionConsentState

    /** The OS reported the user denied the request. Terminal; capture closed. */
    data object Denied : ProjectionConsentState

    /** The OS consent intent was cancelled or never confirmed. Terminal; capture closed. */
    data object Cancelled : ProjectionConsentState

    /** The projection was stopped/revoked after grant, or superseded. Terminal; capture closed. */
    data object Revoked : ProjectionConsentState

    /** The grant was taken into use by the caller. Terminal; capture closed until a new round. */
    data object Consumed : ProjectionConsentState
}

/** Thrown when a capture consumer asks for a usable consent session while none exists. */
class ConsentNotUsableException(state: ProjectionConsentState) :
    IllegalStateException("MediaProjection consent not usable (${state.description})")

class MediaProjectionConsentGate {

    @Volatile var state: ProjectionConsentState = ProjectionConsentState.NotRequested
        private set

    /** Monotonic consent round identity; old image leases can never revive. */
    @Volatile var generation: Long = 0
        private set

    /**
     * Mark that the caller surfaced the OS consent dialog. Only valid before any terminal
     * round or after a closed round; ignored while a grant is live so a new request cannot
     * silently invalidate an in-flight grant without an explicit [onRevoked].
     */
    @Synchronized
    fun onRequestStarted(): Boolean {
        val current = state
        if (current is ProjectionConsentState.Granted) return false
        if (current is ProjectionConsentState.Requesting) return false
        generation++
        state = ProjectionConsentState.Requesting
        return true
    }

    /**
     * Record the OS/user decision for the current request.
     *
     * A grant is accepted only while [state] is [ProjectionConsentState.Requesting] and a
     * non-blank [sessionId] is supplied; anything else fails closed to [Denied] or is ignored
     * without ever producing a usable grant.
     *
     * @return the resulting state, or null when the event was ignored (no state change).
     */
    @Synchronized
    fun onDecision(granted: Boolean, sessionId: String?): ProjectionConsentState? {
        val current = state
        if (granted) {
            if (current !is ProjectionConsentState.Requesting) return null
            val id = sessionId
            if (id.isNullOrEmpty()) return null
            val grantedState = ProjectionConsentState.Granted(id)
            state = grantedState
            return grantedState
        }
        if (current != ProjectionConsentState.Requesting) return null
        state = ProjectionConsentState.Denied
        return ProjectionConsentState.Denied
    }

    /**
     * Record that the consent round was cancelled (dialog dismissed / intent failed). Fails
     * closed for every live round; any live grant must instead go through [onRevoked].
     */
    @Synchronized
    fun onCancelled(): Boolean {
        val current = state
        if (current != ProjectionConsentState.Requesting) return false
        state = ProjectionConsentState.Cancelled
        return true
    }

    /**
     * Record that the projection was stopped or revoked (screen-off, projection onStop
     * callback, user revoke, or superseded by a new session). Fails closed unconditionally:
     * the only path back is a fresh OS consent round via [onRequestStarted].
     */
    @Synchronized
    fun onRevoked(): Boolean {
        val current = state
        if (current is ProjectionConsentState.Revoked) return false
        if (current is ProjectionConsentState.Consumed ||
            current is ProjectionConsentState.Granted ||
            current is ProjectionConsentState.Requesting
        ) {
            state = ProjectionConsentState.Revoked
            return true
        }
        return false
    }

    /**
     * Consume the one-shot grant: the caller has taken the projection into use and this
     * consent can never be reused afterward. Only the holder of the exact granted
     * [sessionId] may consume it; wrong or stale ids are rejected without changing state.
     */
    @Synchronized
    fun onConsumed(sessionId: String?): Boolean {
        val current = state
        val granted = current as? ProjectionConsentState.Granted ?: return false
        if (sessionId == null || sessionId != granted.sessionId) return false
        state = ProjectionConsentState.Consumed
        return true
    }

    /** Whether a non-consumed grant is currently open. Read-only, never side-effecting. */
    @Synchronized
    fun isProbeAllowed(): Boolean = state is ProjectionConsentState.Granted

    /**
     * Return the live one-shot session id, or null when capture must fail closed. The id is
     * a generation/staleness guard for the caller to echo back into [onConsumed], NOT a
     * MediaProjection handle.
     */
    @Synchronized
    fun usableSessionId(): String? =
        (state as? ProjectionConsentState.Granted)?.sessionId

    /**
     * For capture consumers: assert a usable non-consumed grant exists and return its
     * session id, or fail fast with [ConsentNotUsableException]. Callers must supply the
     * returned id to [onConsumed] exactly once when the projection is taken into use.
     */
    @Synchronized
    fun requireUsableSessionId(): String =
        usableSessionId() ?: throw ConsentNotUsableException(state)

    /** Reset to a fresh gate (e.g., after teardown logging). Never resurrects a grant. */
    @Synchronized
    fun reset() {
        generation++
        state = ProjectionConsentState.NotRequested
    }
}

private val ProjectionConsentState.description: String
    get() = when (this) {
        ProjectionConsentState.NotRequested -> "NotRequested"
        ProjectionConsentState.Requesting -> "Requesting"
        is ProjectionConsentState.Granted -> "Granted(sessionId=<opaque>)"
        ProjectionConsentState.Denied -> "Denied"
        ProjectionConsentState.Cancelled -> "Cancelled"
        ProjectionConsentState.Revoked -> "Revoked"
        ProjectionConsentState.Consumed -> "Consumed"
    }
