package ai.closepaw.chatgpt.control

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.*

/** Each visual-sequence key gets its own marker; never reused as proof. */
internal class KeyboardWindowTrace(val keyIndex: Int, val readAttempt: Int, val reuseKeyMap: Boolean = false) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<KeyboardWindowTrace>
    var mark: DisplayWindowChangeJournal.Mark? = null
}

/** Bounded process-local event categories, never raw titles, packages, windows, or generations. */
internal class DisplayWindowChangeJournal(private val nowNs: () -> Long = System::nanoTime) {
    enum class Type(val code: String) { WINDOWS_CHANGED("windows_changed"), WINDOW_STATE_CHANGED("window_state_changed") }
    enum class Owner(val code: String) {
        CHROME("chrome"), SAMSUNG_IME("samsung_ime"), CLOSEPAW("closepaw"), SYSTEM("system"),
        OTHER("other"), UNKNOWN("unknown"),
    }

    companion object {
        private const val CAPACITY = 16
        fun ownerOf(reportedPackage: String?, selfPackage: String): Owner = when (reportedPackage) {
            null, "" -> Owner.UNKNOWN
            selfPackage -> Owner.CLOSEPAW
            "com.android.chrome" -> Owner.CHROME
            "com.samsung.android.honeyboard" -> Owner.SAMSUNG_IME
            "android", "com.android.systemui" -> Owner.SYSTEM
            else -> Owner.OTHER
        }
    }

    data class Mark(val generation: Long, val startedNs: Long, val keyIndex: Int, val readAttempt: Int)
    private data class Entry(val generation: Long, val atNs: Long, val type: Type, val owner: Owner)
    private val entries = ArrayDeque<Entry>()

    @Synchronized fun record(generation: Long, type: Type, owner: Owner) {
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast(Entry(generation, nowNs(), type, owner))
    }

    @Synchronized fun summarize(mark: Mark, throughGeneration: Long): JsonObject {
        val endedNs = nowNs()
        val relevant = entries.filter { it.generation > mark.generation && it.generation <= throughGeneration &&
            it.atNs >= mark.startedNs && it.atNs <= endedNs }
        fun ms(nanos: Long) = (nanos / 1_000_000L).coerceAtLeast(0L)
        return buildJsonObject {
            put("key_index", mark.keyIndex); put("read_attempt", mark.readAttempt)
            put("elapsed_ms", ms(endedNs - mark.startedNs))
            put("unrecorded_count", (throughGeneration - mark.generation - relevant.size).coerceAtLeast(0L))
            put("events", buildJsonArray {
                for (event in relevant) add(buildJsonObject {
                    put("after_action_source_check_ms", ms(event.atNs - mark.startedNs))
                    put("type", event.type.code)
                    put("reported_owner", event.owner.code)
                })
            })
        }
    }
}
