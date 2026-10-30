package ai.closepaw.platform.virtualdisplay

import ai.closepaw.chatgpt.control.ControlSession
import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Only polling is adjustable. Freshness, expiry, consent and masking are not fields. */
internal object ProjectionAcquisitionTuning {
    @Volatile private var state: ProjectionTuningState? = null
    @Synchronized fun initialize(context: Context) {
        if (state != null) return
        state = ProjectionTuningState(AtomicProjectionTuningPersistence(
            AtomicFile(File(context.applicationContext.filesDir, "projection-polling-v1"))))
    }
    fun initialized() = state != null
    fun storageState() = state?.storageState() ?: "initializing"
    fun snapshot() = state?.snapshot() ?: ProjectionTuningValues()
    fun update(session: ControlSession?, sessionId: String?, authorized: Boolean,
               expectedRevision: Int, timeoutMs: Long, pollMs: Long): ProjectionTuningOutcome =
        state?.update(session, sessionId, authorized, expectedRevision, timeoutMs, pollMs) ?: ProjectionTuningOutcome.REFUSED
}

internal data class ProjectionTuningValues(val revision: Int = 0, val timeoutMs: Long = 1500L, val pollMs: Long = 25L) {
    fun valid() = revision >= 0 && timeoutMs in 1500L..3000L && pollMs in 25L..100L
    fun encode() = "$revision,$timeoutMs,$pollMs"
    companion object {
        fun decode(raw: String): ProjectionTuningValues? = runCatching {
            val parts = raw.split(',')
            require(parts.size == 3)
            ProjectionTuningValues(parts[0].toInt(), parts[1].toLong(), parts[2].toLong()).takeIf { it.valid() }
        }.getOrNull()
    }
}

internal enum class ProjectionTuningOutcome { APPLIED, REFUSED, STORAGE_UNKNOWN }

internal interface ProjectionTuningPersistence {
    fun load(): ProjectionTuningValues?
    /** REFUSED preserves the previous document; post-finish uncertainty is explicit. */
    fun commit(values: ProjectionTuningValues): ProjectionTuningOutcome
}

/** AtomicFile retains the previous document on failed writes, unlike SharedPreferences'
 * in-memory mutation even when commit returns false. No Context is retained. */
internal class AtomicProjectionTuningPersistence(private val file: AtomicFile) : ProjectionTuningPersistence {
    override fun load(): ProjectionTuningValues? = runCatching {
        file.openRead().use { input ->
            val bytes = ByteArray(129)
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                if (count == 0) return@use null
                size += count
            }
            if (size > 128) null else ProjectionTuningValues.decode(String(bytes, 0, size, Charsets.UTF_8))
        }
    }.getOrNull()
    override fun commit(values: ProjectionTuningValues): ProjectionTuningOutcome {
        var stream: java.io.FileOutputStream? = null
        var finishing = false
        return try {
            stream = file.startWrite()
            stream.write(values.encode().toByteArray(Charsets.UTF_8))
            stream.fd.sync() // finishWrite itself can log a sync failure without throwing
            finishing = true
            file.finishWrite(stream)
            if (load() == values) ProjectionTuningOutcome.APPLIED else ProjectionTuningOutcome.STORAGE_UNKNOWN
        } catch (_: Exception) {
            if (finishing) ProjectionTuningOutcome.STORAGE_UNKNOWN else {
                runCatching { file.failWrite(stream) }
                ProjectionTuningOutcome.REFUSED
            }
        }
    }
}

internal class ProjectionTuningState(private val persistence: ProjectionTuningPersistence) {
    @Volatile private var current = persistence.load()?.takeIf { it.valid() } ?: ProjectionTuningValues()
    @Volatile private var uncertain = false
    fun snapshot() = current
    fun storageState() = if (uncertain) "unknown_restart_required" else "confirmed"
    fun update(session: ControlSession?, sessionId: String?, authorized: Boolean,
               expectedRevision: Int, timeoutMs: Long, pollMs: Long): ProjectionTuningOutcome {
        if (session == null || !authorized) return ProjectionTuningOutcome.REFUSED
        // ControlSession pause/end/start use this same monitor. The final authority
        // check and durable write form one mutation boundary; caller flags cannot
        // manufacture a matching, explicit, active server-side session.
        return synchronized(session) {
            if (!session.explicit || session.state != ControlSession.State.ACTIVE ||
                session.refusal(sessionId, mutation = true) != null) return@synchronized ProjectionTuningOutcome.REFUSED
            commit(expectedRevision, timeoutMs, pollMs)
        }
    }
    @Synchronized private fun commit(expectedRevision: Int, timeoutMs: Long, pollMs: Long): ProjectionTuningOutcome {
        if (uncertain) return ProjectionTuningOutcome.STORAGE_UNKNOWN
        val old = current
        if (expectedRevision != old.revision || old.revision == Int.MAX_VALUE) return ProjectionTuningOutcome.REFUSED
        val next = ProjectionTuningValues(old.revision + 1, timeoutMs, pollMs)
        if (!next.valid()) return ProjectionTuningOutcome.REFUSED
        val outcome = persistence.commit(next)
        if (outcome == ProjectionTuningOutcome.APPLIED) current = next
        if (outcome == ProjectionTuningOutcome.STORAGE_UNKNOWN) uncertain = true
        return outcome
    }
}
