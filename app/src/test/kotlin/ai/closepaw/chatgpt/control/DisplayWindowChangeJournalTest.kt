package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DisplayWindowChangeJournalTest {
    private fun events(j: DisplayWindowChangeJournal, from: Long, through: Long): JsonArray =
        j.summarize(DisplayWindowChangeJournal.Mark(from, 0L, 1, 1), through)
            .getValue("events").jsonArray

    @Test fun unknownReportedOwnerCanBeCorrelatedWithRecentlyCertifiedImeWithoutExposingIds() {
        var now = 100_000_000L
        val j = DisplayWindowChangeJournal { now }
        j.observeKnownWindows(1101, 2202)
        now += 1_000_000L
        j.record(2, DisplayWindowChangeJournal.Type.WINDOWS_CHANGED,
            DisplayWindowChangeJournal.Owner.UNKNOWN, eventWindowId = 2202,
            windowChanges = 1 or 8, eventDisplayId = 0)
        val diagnostic = events(j, 1, 2).single().jsonObject
        assertEquals("unknown", diagnostic.getValue("reported_owner").jsonPrimitive.content)
        assertEquals("ime", diagnostic.getValue("event_window_match").jsonPrimitive.content)
        assertEquals(9, diagnostic.getValue("window_change_mask").jsonPrimitive.int)
        assertEquals(false, diagnostic.getValue("unknown_change_bits").jsonPrimitive.boolean)
        assertEquals("default", diagnostic.getValue("event_display_class").jsonPrimitive.content)
        assertFalse(diagnostic.toString().contains("2202"))
        assertFalse(diagnostic.toString().contains("1101"))
    }

    @Test fun unmatchedOrMissingWindowRemainsDiagnosticOnlyAndUntrusted() {
        var now = 100_000_000L
        val j = DisplayWindowChangeJournal { now }
        j.observeKnownWindows(41, 42)
        now += 1_000_000L
        j.record(2, DisplayWindowChangeJournal.Type.WINDOWS_CHANGED,
            DisplayWindowChangeJournal.Owner.CLOSEPAW, eventWindowId = 43)
        now += 1_000_000L
        j.record(3, DisplayWindowChangeJournal.Type.WINDOW_STATE_CHANGED,
            DisplayWindowChangeJournal.Owner.UNKNOWN, eventWindowId = -1, windowChanges = 1024)
        val entries = events(j, 1, 3).map { it.jsonObject }
        assertEquals("other", entries[0].getValue("event_window_match").jsonPrimitive.content)
        assertEquals("unknown", entries[1].getValue("event_window_match").jsonPrimitive.content)
        assertEquals(0, entries[1].getValue("window_change_mask").jsonPrimitive.int)
    }

    @Test fun staleWindowIdentityCannotBeMisclassifiedAsCurrent() {
        var now = 1_000_000L
        val j = DisplayWindowChangeJournal { now }
        j.observeKnownWindows(41, 42)
        now += 31_000_000_000L
        j.record(2, DisplayWindowChangeJournal.Type.WINDOWS_CHANGED,
            DisplayWindowChangeJournal.Owner.UNKNOWN, eventWindowId = 42, eventDisplayId = 1)
        val item = events(j, 1, 2).single().jsonObject
        assertEquals("unknown", item.getValue("event_window_match").jsonPrimitive.content)
        assertEquals("other", item.getValue("event_display_class").jsonPrimitive.content)
    }

    @Test fun unknownFutureBitsAreFlaggedAndJournalStillBounded() {
        var now = 1_000_000L
        val j = DisplayWindowChangeJournal { now }
        j.observeKnownWindows(51, null)
        for (n in 1..20) {
            now += 1_000_000L
            j.record(n.toLong(), DisplayWindowChangeJournal.Type.WINDOWS_CHANGED,
                DisplayWindowChangeJournal.Owner.UNKNOWN, eventWindowId = 51,
                windowChanges = 0x1000 or 0x10)
        }
        val entries = events(j, 0, 20)
        assertEquals(16, entries.size)
        assertEquals("application", entries.last().jsonObject.getValue("event_window_match").jsonPrimitive.content)
        assertEquals(16, entries.last().jsonObject.getValue("window_change_mask").jsonPrimitive.int)
        assertTrue(entries.last().jsonObject.getValue("unknown_change_bits").jsonPrimitive.boolean)
    }
}
