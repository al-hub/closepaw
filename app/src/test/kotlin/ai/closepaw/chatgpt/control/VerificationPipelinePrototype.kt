package ai.closepaw.chatgpt.control

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore

/** EXPERIMENT ONLY. Never registered as an operation or connected to an Android ControlPort.
 * A single owner dispatcher serializes admission and guarded execute+snapshot. Only pure
 * verification overlaps. A production adapter would need native-boundary guards as well.
 */
internal class VerificationPipelinePrototype(private val limit: Int) {
    enum class State { STARTED, SNAPSHOT, VERIFIED, OUTCOME_UNKNOWN }
    data class Proof(val command: Int, val epoch: Long, val generation: Long,
        val expected: Long, val actual: Long, val fresh: Boolean = true, val hasResult: Boolean = true)
    data class Entry(val command: Int, val state: State)
    data class Result(val entries: List<Entry>, val verifiedPrefix: Int, val peakInFlight: Int,
        val reason: String?, val residualQueue: Int)
    private val stopped = AtomicBoolean(false)
    private val ledger = linkedMapOf<Int, State>()
    private var terminal: String? = null
    private var producer: Job? = null
    private var verifier: Job? = null
    private var queue: Channel<Proof>? = null
    private var peak = 0
    private var used = false
    private val queued = AtomicInteger()
    init { require(limit in 1..2) }

    @Synchronized fun stop(reason: String) {
        if (!stopped.compareAndSet(false, true)) return
        // Fixed allowlist: experiment results never export arbitrary exception/screen messages.
        terminal = if (reason in setOf("x", "app_changed", "locked", "permission_revoked",
            "user_takeover", "session_ended", "stale", "proof_mismatch", "event_only",
            "verification_failed", "exception", "cancelled")) reason else "exception"
        ledger.entries.forEach { if (it.value != State.VERIFIED) it.setValue(State.OUTCOME_UNKNOWN) }
        queue?.cancel(); producer?.cancel(); verifier?.cancel()
    }
    @Synchronized private fun admit(id: Int): Boolean {
        if (stopped.get()) return false
        ledger[id] = State.STARTED
        peak = maxOf(peak, ledger.values.count { it != State.VERIFIED })
        return true
    }
    @Synchronized private fun update(id: Int, state: State) {
        if (!stopped.get()) ledger[id] = state
    }
    @Synchronized fun result(): Result {
        var prefix = 0
        while (ledger[prefix + 1] == State.VERIFIED) prefix++
        return Result(ledger.map { Entry(it.key, it.value) }, prefix, peak, terminal, queued.get())
    }

    suspend fun run(epoch: Long = 1, generation: Long = 1,
        guard: () -> String? = { null },
        executeAndSnapshot: suspend (Int) -> Proof,
        verify: suspend (Proof) -> Boolean = { it.actual == it.expected },
    ): Result = coroutineScope {
        check(!used) { "A terminated experiment cannot restart or replay" }; used = true
        val pending = Channel<Proof>(limit, onUndeliveredElement = { queued.decrementAndGet() })
        queue = pending
        val slots = Semaphore(limit)
        fun safe(): Boolean {
            guard()?.let { stop(it) }
            return !stopped.get()
        }
        // LAZY jobs are both installed before either can signal stop.
        producer = launch(start = CoroutineStart.LAZY) {
            try {
                for (id in 1..9) {
                    slots.acquire()
                    ensureActive()
                    if (!safe() || !admit(id)) break
                    val proof = executeAndSnapshot(id)
                    ensureActive()
                    if (!safe()) break
                    when {
                        proof.command != id || proof.epoch != epoch || proof.generation != generation -> stop("proof_mismatch")
                        proof.expected != (1..id).joinToString("").toLong() -> stop("proof_mismatch")
                        !proof.fresh -> stop("stale")
                        !proof.hasResult -> stop("event_only")
                    }
                    if (stopped.get()) break
                    update(id, State.SNAPSHOT)
                    queued.incrementAndGet()
                    pending.send(proof)
                }
            } catch (cancelled: CancellationException) { stop("cancelled"); throw cancelled }
            catch (_: Exception) { stop("exception") }
            finally { pending.close() }
        }
        verifier = launch(start = CoroutineStart.LAZY) {
            try {
                for (proof in pending) {
                    queued.decrementAndGet()
                    if (!safe()) break
                    val valid = verify(proof)
                    ensureActive()
                    if (!safe()) break
                    if (!valid) { stop("verification_failed"); break }
                    update(proof.command, State.VERIFIED)
                    slots.release()
                }
            } catch (cancelled: CancellationException) { stop("cancelled"); throw cancelled }
            catch (_: Exception) { stop("exception") }
        }
        producer!!.start(); verifier!!.start()
        try { joinAll(producer!!, verifier!!) }
        catch (cancelled: CancellationException) { stop("cancelled"); throw cancelled }
        finally { pending.cancel() }
        result()
    }
}
