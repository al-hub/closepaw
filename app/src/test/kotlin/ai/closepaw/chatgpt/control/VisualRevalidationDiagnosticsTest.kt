package ai.closepaw.chatgpt.control

import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class VisualRevalidationDiagnosticsTest {
    private fun proof() = VisualTargetProof(
        ScreenImage(2, 2, "image/jpeg", byteArrayOf(1), ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
        "private_hash", TapBounds(0, 0, 2, 2), "private_context", 0,
        scope = "app", sourceWindowId = 7, sourceDisplayId = 0, rotation = 0,
        disclosedRegions = listOf(TapBounds(0, 0, 2, 2)), editorPrivacyGeneration = 1
    )

    @Test fun `records only boolean comparisons and identifies changed fields`() {
        val original = proof()
        val changed = original.copy(pixelHash = "other_hash", rotation = 1, sourceWindowId = 8)
        val metrics = VisualRevalidationDiagnostics.metrics(original, changed)
        val expected = mapOf(
            "pixels_changed" to true, "window_bounds_changed" to false,
            "context_changed" to false, "source_window_changed" to true,
            "scope_changed" to false, "source_display_changed" to false,
            "rotation_changed" to true, "disclosed_regions_changed" to false,
            "editor_privacy_generation_changed" to false
        )
        assertEquals(expected.mapValues { JsonPrimitive(it.value) }, metrics)
    }

    @Test fun `all comparisons are false for identical proofs`() {
        val original = proof()
        assertEquals(setOf(JsonPrimitive(false)), VisualRevalidationDiagnostics.metrics(original, original).values.toSet())
    }

    @Test fun `every compared property has its own flag`() {
        val original = proof()
        val changes = mapOf(
            "pixels_changed" to original.copy(pixelHash = "different"),
            "window_bounds_changed" to original.copy(window = TapBounds(0, 0, 1, 1)),
            "context_changed" to original.copy(context = "different"),
            "source_window_changed" to original.copy(sourceWindowId = 8),
            "scope_changed" to original.copy(scope = "display"),
            "source_display_changed" to original.copy(sourceDisplayId = 1),
            "rotation_changed" to original.copy(rotation = 1),
            "disclosed_regions_changed" to original.copy(disclosedRegions = emptyList()),
            "editor_privacy_generation_changed" to original.copy(editorPrivacyGeneration = 2)
        )
        changes.forEach { (name, changed) ->
            val flags = VisualRevalidationDiagnostics.metrics(original, changed)
            assertEquals(setOf(name), flags.filterValues { it == JsonPrimitive(true) }.keys)
        }
    }
}
