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
    fun snapshot() = state?.snapshot() ?: ProjectionTuningValues()
    fun update(session: ControlSession?, sessionId: String?, authorized: Boolean,
               expectedRevision: Int, timeoutMs: Long, pollMs: Long): Boolean =
        state?.update(session, sessionId, authorized, expectedRevision, timeoutMs, pollMs) ?: false
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

internal interface ProjectionTuningPersistence {
    fun load(): ProjectionTuningValues?
    /** On failure the previous durable document must remain intact. */
    fun commit(values: ProjectionTuningValues): Boolean
}

/** AtomicFile retains the previous document on failed writes, unlike SharedPreferences'
 * in-memory mutation even when commit returns false. No Context is retained. */
internal class AtomicProjectionTuningPersistence(private val file: AtomicFile) : ProjectionTuningPersistence {
    override fun load(): ProjectionTuningValues? = runCatching {
        val bytes = file.readFully()
        if (bytes.size > 128) null else ProjectionTuningValues.decode(bytes.toString(Charsets.UTF_8))
    }.getOrNull()
    override fun commit(values: ProjectionTuningValues): Boolean {
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = file.startWrite()
            stream.write(values.encode().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            // Verify the durable document before publishing a new in-process snapshot.
            load() == values
        } catch (_: Exception) {
            file.failWrite(stream)
            false
        }
    }
}

internal class ProjectionTuningState(private val persistence: ProjectionTuningPersistence) {
    @Volatile private var current = persistence.load()?.takeIf { it.valid() } ?: ProjectionTuningValues()
    fun snapshot() = current
    fun update(session: ControlSession?, sessionId: String?, authorized: Boolean,
               expectedRevision: Int, timeoutMs: Long, pollMs: Long): Boolean {
        if (session == null || !authorized) return false
        // ControlSession pause/end/start use this same monitor. The final authority
        // check and durable write form one mutation boundary; caller flags cannot
        // manufacture a matching, explicit, active server-side session.
        return synchronized(session) {
            if (!session.explicit || session.state != ControlSession.State.ACTIVE ||
                session.refusal(sessionId, mutation = true) != null) return@synchronized false
            commit(expectedRevision, timeoutMs, pollMs)
        }
    }
    @Synchronized private fun commit(expectedRevision: Int, timeoutMs: Long, pollMs: Long): Boolean {
        val old = current
        if (expectedRevision != old.revision || old.revision == Int.MAX_VALUE) return false
        val next = ProjectionTuningValues(old.revision + 1, timeoutMs, pollMs)
        if (!next.valid() || !persistence.commit(next)) return false
        current = next
        return true
    }
}
