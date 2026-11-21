package ai.closepaw.chatgpt.control

import ai.closepaw.chatgpt.control.ChromeTreeRegions.Region
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Reproduction of the 2026-11-21 Galaxy v0.1.143 key-2 refusal in issue #147.
 *  This is diagnostic/safety evidence; "other" is NOT proof a window is harmless.
 */
class GalaxyOtherWindowBoundsRegressionTest {
    private fun node(index: Int, parent: Int, changed: String = "unchanged") = buildJsonObject {
        put("id", index)
        put("parent", parent)
        put("text_hash", changed)
    }

    private fun chromeRegions(suggestionChanged: Boolean): ChromeTreeRegions.Fingerprints =
        ChromeTreeRegions().apply {
            add(Region.OTHER, node(0, -1))
            add(Region.SUGGESTIONS, node(1, 0))
            if (suggestionChanged) add(Region.SUGGESTIONS, node(2, 1, "new suggestion"))
            add(Region.EDITOR, node(if (suggestionChanged) 3 else 2, 0))
            add(Region.OTHER, node(if (suggestionChanged) 4 else 3, 0))
        }.fingerprints()

    @Test fun otherWindowBoundsEventsRemainBlockingDespiteStableEditorAndIme() {
        // Actual v0.1.143 diagnostic: key 1 committed, then at key 2/read attempt 2
        // two unknown-owner/other-window BOUNDS events occurred at 125ms and 344ms.
        // Synthetic IDs are used only for correlation; they must never be serialized.
        var now = 1_000_000_000L
        val journal = DisplayWindowChangeJournal { now }
        journal.observeKnownWindows(applicationWindow = 1001, imeWindow = 2002)
        val mark = DisplayWindowChangeJournal.Mark(4L, now, keyIndex = 2, readAttempt = 2)

        now += 125_000_000L
        journal.record(5L, DisplayWindowChangeJournal.Type.WINDOWS_CHANGED,
            DisplayWindowChangeJournal.Owner.UNKNOWN, eventWindowId = 3003,
            windowChanges = 8, eventDisplayId = 0)
        now += 219_000_000L
        journal.record(6L, DisplayWindowChangeJournal.Type.WINDOWS_CHANGED,
            DisplayWindowChangeJournal.Owner.UNKNOWN, eventWindowId = 3003,
            windowChanges = 8, eventDisplayId = 0)
        now += 872_000_000L

        val evidence = journal.summarize(mark, 6L)
        assertEquals(2, evidence.getValue("key_index").jsonPrimitive.int)
        assertEquals(2, evidence.getValue("read_attempt").jsonPrimitive.int)
        assertEquals(1216, evidence.getValue("elapsed_ms").jsonPrimitive.int)
        assertEquals(0, evidence.getValue("unrecorded_count").jsonPrimitive.int)
        val events = evidence.getValue("events").jsonArray.map { it.jsonObject }
        assertEquals(2, events.size)
        assertEquals(listOf(125, 344), events.map {
            it.getValue("after_action_source_check_ms").jsonPrimitive.int
        })
        events.forEach {
            assertEquals("windows_changed", it.getValue("type").jsonPrimitive.content)
            assertEquals("unknown", it.getValue("reported_owner").jsonPrimitive.content)
            assertEquals("other", it.getValue("event_window_match").jsonPrimitive.content)
            assertEquals(8, it.getValue("window_change_mask").jsonPrimitive.int)
            assertFalse(it.getValue("unknown_change_bits").jsonPrimitive.boolean)
            assertEquals("default", it.getValue("event_display_class").jsonPrimitive.content)
        }
        for (rawId in listOf("1001", "2002", "3003")) {
            assertFalse("Raw window ID must stay private", evidence.toString().contains(rawId))
        }

        // Simulate the independently observed Chrome suggestion-only tree change:
        // a changing suggestion list is not authority to keep using stale proof.
        val beforeParts = KeyboardVisualFingerprintParts(
            "same-frame-context", "old-chrome-tree", "same-ime-tree", chromeRegions(false))
        val before = ControlFrame("com.android.chrome", 2, "old-full-fingerprint",
            emptyList(), keyboardVisualFingerprintParts = beforeParts)
        val after = before.copy(fingerprint = "new-full-fingerprint",
            keyboardVisualFingerprintParts = beforeParts.copy(
                appTree = "new-chrome-tree", chrome = chromeRegions(true)))
        val change = KeyboardVisualPostCheck.describe(before, after)
        assertTrue(change.getValue("chrome_suggestions_changed").jsonPrimitive.boolean)
        assertFalse(change.getValue("chrome_editor_changed").jsonPrimitive.boolean)
        assertFalse(change.getValue("chrome_other_changed").jsonPrimitive.boolean)
        assertEquals("screen_changed", GenericControlActionPolicy.refusal(before, after, "visual_key"))

        // Preserve the native block and its bounded, categorized telemetry.
        // Do not classify BOUNDS+other as safe or authorize a second-key touch here.
        val failure = VisualSourceFailure(VisualSourceStage.NATIVE_ACTION_VALIDATION,
            VisualSourceCondition.DISPLAY_WINDOW_CHANGED).copy(windowEvents = evidence)
        assertEquals(evidence, failure.json().getValue("window_event_interval"))
    }
}
