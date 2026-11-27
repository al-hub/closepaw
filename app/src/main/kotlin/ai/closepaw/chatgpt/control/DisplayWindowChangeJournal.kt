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
    // Diagnostic only. An ID match never authorizes a touch or exempts a window event.
    enum class WindowMatch(val code: String) {
        APPLICATION("application"), IME("ime"), CONTROL_OVERLAY("control_overlay"), OTHER("other"), UNKNOWN("unknown"),
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
    private data class Entry(val generation: Long, val atNs: Long, val type: Type, val owner: Owner,
        val windowMatch: WindowMatch, val windowChangeMask: Int, val unknownChangeBits: Boolean,
        val displayClass: String)
    private val entries = ArrayDeque<Entry>()
    private var knownApplicationWindow = -1
    private var knownImeWindow = -1
    private var knownAtNs = -1L
    // Only previously admitted native window identities are remembered privately. Never log their IDs.
    @Synchronized fun observeKnownWindows(applicationWindow: Int, imeWindow: Int?) {
        if (applicationWindow < 0) return
        knownApplicationWindow = applicationWindow
        knownImeWindow = imeWindow?.takeIf { it >= 0 } ?: -1
        knownAtNs = nowNs()
    }

    @Synchronized fun record(generation: Long, type: Type, owner: Owner,
        eventWindowId: Int = -1, windowChanges: Int = 0, eventDisplayId: Int = -1,
        isControlOverlay: Boolean = false) {
        val at = nowNs()
        // Stale or absent evidence remains unknown; never infer harmlessness from a category.
        val match = when {
            isControlOverlay -> WindowMatch.CONTROL_OVERLAY
            eventWindowId < 0 || knownAtNs < 0 || at < knownAtNs ||
                at - knownAtNs > 30_000_000_000L -> WindowMatch.UNKNOWN
            eventWindowId == knownApplicationWindow -> WindowMatch.APPLICATION
            eventWindowId == knownImeWindow -> WindowMatch.IME
            else -> WindowMatch.OTHER
        }
        val mask = if (type == Type.WINDOWS_CHANGED) windowChanges else 0
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast(Entry(generation, at, type, owner, match,
            mask and 0x7ff, mask and 0x7ff.inv() != 0,
            when { eventDisplayId < 0 -> "unknown"; eventDisplayId == 0 -> "default"; else -> "other" }))
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
                    put("event_window_match", event.windowMatch.code)
                    // Only the documented window-change bitset; never a raw window ID or package.
                    put("window_change_mask", event.windowChangeMask)
                    put("unknown_change_bits", event.unknownChangeBits)
                    put("event_display_class", event.displayClass)
                })
            })
        }
    }
}
