package ai.closepaw.chatgpt.control

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** Bounds acquisition as well as preparation, without suspending while waiting for the shared lease. */
internal object LocalImeGeometryLease {
    suspend fun <T : Any> capture(mutex: Mutex, timeoutMs: Long, busy: () -> T, timedOut: () -> T,
        block: suspend () -> T): T {
        if (!mutex.tryLock()) return busy()
        return try { withTimeoutOrNull(timeoutMs) { block() } ?: timedOut() }
        finally { mutex.unlock() }
    }
}
