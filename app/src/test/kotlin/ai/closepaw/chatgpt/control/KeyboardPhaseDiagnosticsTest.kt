package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.control.ChromeTreeRegions.Region
import ai.closepaw.chatgpt.control.DisplayWindowChangeJournal.Owner
import ai.closepaw.chatgpt.control.DisplayWindowChangeJournal.Type
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class KeyboardPhaseDiagnosticsTest {
    private fun node(index: Int, parent: Int, changed: String = "unchanged") = buildJsonObject {
        put("id", index); put("parent", parent); put("text_hash", changed)
    }

    @Test fun suggestionSubtreeChangeDoesNotShiftEditorOrOtherFingerprints() {
        assertEquals(Region.EDITOR, ChromeTreeRegions.regionOf("com.android.chrome:id/url_bar", Region.OTHER))
        val list = ChromeTreeRegions.regionOf("com.android.chrome:id/omnibox_suggestions_dropdown", Region.OTHER)
        assertEquals(Region.SUGGESTIONS, list)
        assertEquals(list, ChromeTreeRegions.regionOf("", list))
        fun parts(extra: Boolean): ChromeTreeRegions.Fingerprints = ChromeTreeRegions().apply {
            add(Region.OTHER, node(0, -1))
            add(list, node(1, 0))
            if (extra) add(list, node(2, 1, "only suggestion changes"))
            add(Region.EDITOR, node(if (extra) 3 else 2, 0))
            add(Region.OTHER, node(if (extra) 4 else 3, 0))
        }.fingerprints()
        val first = KeyboardVisualFingerprintParts("context", "app-before", "ime", parts(false))
        val later = first.copy(appTree = "app-after", chrome = parts(true))
        val before = ControlFrame("com.android.chrome", 2, "full-before", emptyList(), keyboardVisualFingerprintParts = first)
        val after = before.copy(fingerprint = "full-after", keyboardVisualFingerprintParts = later)
        assertEquals("screen_changed", GenericControlActionPolicy.refusal(before, after, "visual_key"))
        val change = KeyboardVisualPostCheck.describe(before, after)
        assertFalse(change.getValue("chrome_editor_changed").jsonPrimitive.boolean)
        assertTrue(change.getValue("chrome_suggestions_changed").jsonPrimitive.boolean)
        assertFalse(change.getValue("chrome_other_changed").jsonPrimitive.boolean)
        assertFalse(change.toString().contains("only suggestion changes"))
    }

    @Test fun neverObservedChromeRegionIsNotReportedAsUnchanged() {
        val regions = ChromeTreeRegions().apply { add(Region.OTHER, node(0, -1)) }.fingerprints()
        val parts = KeyboardVisualFingerprintParts("context", "app-before", "ime", regions)
        val first = ControlFrame("com.android.chrome", 2, "before", emptyList(), keyboardVisualFingerprintParts = parts)
        val second = first.copy(fingerprint = "after", keyboardVisualFingerprintParts = parts.copy(appTree = "app-after"))
        val change = KeyboardVisualPostCheck.describe(first, second)
        assertFalse("chrome_editor_changed" in change)
        assertFalse("chrome_suggestions_changed" in change)
    }

    @Test fun windowIntervalUsesMonotonicRelativeTimeAndGenerationFence() {
        var now = 1_000_000L
        val journal = DisplayWindowChangeJournal { now }
        journal.record(4, Type.WINDOWS_CHANGED, Owner.CHROME)
        val mark = DisplayWindowChangeJournal.Mark(4, 2_000_000L, 2, 2)
        now = 3_000_000L; journal.record(5, Type.WINDOW_STATE_CHANGED, Owner.CLOSEPAW)
        now = 5_000_000L; journal.record(6, Type.WINDOWS_CHANGED, Owner.UNKNOWN)
        now = 6_000_000L; journal.record(7, Type.WINDOWS_CHANGED, Owner.OTHER)
        val result = journal.summarize(mark, 6)
        assertEquals(Json.parseToJsonElement("""{"key_index":2,"read_attempt":2,"elapsed_ms":4,"unrecorded_count":0,"events":[
            {"after_action_source_check_ms":1,"type":"window_state_changed","reported_owner":"closepaw"},
            {"after_action_source_check_ms":3,"type":"windows_changed","reported_owner":"unknown"}]}"""), result)
        val failure = VisualSourceFailure(VisualSourceStage.NATIVE_ACTION_VALIDATION, VisualSourceCondition.DISPLAY_WINDOW_CHANGED)
        assertFalse("window_event_interval" in failure.json())
        assertEquals(result, failure.copy(windowEvents = result).json().getValue("window_event_interval"))
    }

    @Test fun boundedJournalReportsMissingEntriesAndOnlyFixedOwnerCategories() {
        var now = 1_000_000L
        val journal = DisplayWindowChangeJournal { now }
        for (generation in 1L..21L) {
            now += 1_000_000L
            journal.record(generation, Type.WINDOWS_CHANGED, Owner.OTHER)
        }
        val summary = journal.summarize(DisplayWindowChangeJournal.Mark(0, 1_000_000L, 3, 1), 21)
        assertEquals(16, summary.getValue("events").jsonArray.size)
        assertEquals(5, summary.getValue("unrecorded_count").jsonPrimitive.int)
        val self = "ai.closepaw"
        assertEquals(Owner.UNKNOWN, DisplayWindowChangeJournal.ownerOf(null, self))
        assertEquals(Owner.OTHER, DisplayWindowChangeJournal.ownerOf("unknown.example", self))
        assertEquals(Owner.CLOSEPAW, DisplayWindowChangeJournal.ownerOf(self, self))
        assertEquals(Owner.CHROME, DisplayWindowChangeJournal.ownerOf("com.android.chrome", self))
        assertEquals(Owner.SAMSUNG_IME, DisplayWindowChangeJournal.ownerOf("com.samsung.android.honeyboard", self))
        assertEquals(Owner.SYSTEM, DisplayWindowChangeJournal.ownerOf("com.android.systemui", self))
    }
}
