package ai.closepaw.chatgpt

import ai.closepaw.bridge.TermuxJobReadiness
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Authenticated readiness only. Never submits a command, repairs pairing, or owns a UI session. */
internal class TermuxJobReadinessGate(
    private val probe: suspend () -> TermuxJobReadiness,
    private val recover: suspend () -> String?,
    private val clockMillis: () -> Long,
    private val cooldownMs: Long = 30_000L,
) {
    private val mutex = Mutex()
    private var lastAttempt: Long? = null
    private var recoveryReason: String? = null

    suspend fun check(): TermuxJobReadiness {
        val initial = probe()
        if (initial.ready || !initial.isOffline()) return initial
        return mutex.withLock {
            // Another caller may have restored the bridge while this caller waited.
            val current = probe()
            if (current.ready || !current.isOffline()) return@withLock current
            val now = clockMillis()
            val previous = lastAttempt
            if (previous == null || now - previous >= cooldownMs) {
                lastAttempt = now
                recoveryReason = recover()
            }
            val verified = probe()
            if (verified.ready || !verified.isOffline()) verified
            else TermuxJobReadiness(false, recoveryReason ?: verified.reason)
        }
    }

    private fun TermuxJobReadiness.isOffline() =
        reason?.startsWith("bridge_daemon_unreachable:") == true
}
