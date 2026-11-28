package ai.closepaw.chatgpt.control

/**
 * Two-stage pre-touch safety gate evaluated inside the dispatch continuation:
 * 1. Pre-Binder fail-fast in-memory check (zero OS/Binder calls when already refused).
 * 2. OS/policy Binder guards (Keyguard/Power, mutationBlock, packageAllowed).
 * 3. Post-Binder in-memory recheck immediately before timing mark and OS dispatchGesture.
 *
 * Note: Synchronous in-memory rechecks minimize the post-Binder race window immediately
 * before OS dispatch, but OS asynchronous gesture dispatch itself cannot be made atomic
 * with subsequent OS state changes.
 */
internal object GestureDispatchGate {
    private val authorityBoundaryRefusals = setOf(
        "control_session_changed_observe_again",
        "screen_share_revoked",
        "keyboard_sequence_window_recheck_consent_changed",
    )

    fun refusal(session: ControlSession?, expectedEpoch: Long, active: Boolean): String? = when {
        !active -> "gesture_cancelled_before_dispatch"
        session == null || session.epoch != expectedEpoch -> "control_session_changed_observe_again"
        else -> session.refusal(session.id, mutation = true)
    }

    /**
     * Evaluates Stage 1 (pre-Binder fail-fast), then Binder/OS policy guards, then Stage 2
     * (post-Binder in-memory recheck right before dispatch).
     *
     * Precedence when Binder/OS policy returns a refusal AND post-Binder state also changed:
     * 1. Cancellation / Session / Epoch / Authority / Consent revocation (fail-closed, never masked).
     * 2. Existing Binder/OS policy refusal (preserved; never downgraded into recovery elapsed/deadline).
     * 3. When Binder/OS policy allows, Stage 2 in-memory refusal (Authority/Cancel > Deadline > 15s episode).
     */
    fun evaluateTwoStageRefusal(
        session: () -> ControlSession?,
        expectedEpoch: Long,
        active: () -> Boolean,
        boundaryRefusal: (() -> String?)? = null,
        policyRefusal: () -> String?,
    ): String? {
        // Stage 1: pre-Binder fail-fast (zero Binder calls if already refused).
        refusal(session(), expectedEpoch, active())?.let { return it }
        boundaryRefusal?.invoke()?.let { return it }

        // OS / Binder policy guards (Keyguard/Power, mutationBlock, packageAllowed).
        val binderRefusal = policyRefusal()

        // Stage 2: post-Binder in-memory recheck immediately before dispatchGesture.
        val postGateRefusal = refusal(session(), expectedEpoch, active())
        if (postGateRefusal != null) return postGateRefusal
        val postBoundaryRefusal = boundaryRefusal?.invoke()
        if (binderRefusal != null) {
            if (postBoundaryRefusal != null && postBoundaryRefusal in authorityBoundaryRefusals) {
                return postBoundaryRefusal
            }
            return binderRefusal
        }
        return postBoundaryRefusal
    }

    /**
     * Production dispatch helper called directly by AndroidControlPort.tap so host tests
     * exercise the exact same 2-stage guard, mark-before-dispatch, and refusal/cancellation paths.
     */
    fun guardAndDispatch(
        session: () -> ControlSession?,
        expectedEpoch: Long,
        active: () -> Boolean,
        boundaryRefusal: (() -> String?)? = null,
        policyRefusal: () -> String?,
        onRefused: (String) -> Unit,
        onMarkDispatch: () -> Unit,
        onDispatch: () -> Unit,
    ): Boolean {
        if (!active()) return false
        val finalRefusal = evaluateTwoStageRefusal(
            session = session,
            expectedEpoch = expectedEpoch,
            active = active,
            boundaryRefusal = boundaryRefusal,
            policyRefusal = policyRefusal,
        )
        if (finalRefusal != null) {
            if (active()) onRefused(finalRefusal)
            return false
        }
        if (!active()) return false
        onMarkDispatch()
        onDispatch()
        return true
    }
}
