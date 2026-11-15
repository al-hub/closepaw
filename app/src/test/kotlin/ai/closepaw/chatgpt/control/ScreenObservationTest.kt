package ai.closepaw.chatgpt.control

import ai.closepaw.model.ScreenImage
import ai.closepaw.model.ScreenImageSource
import ai.closepaw.security.ImageRevocationAuthority
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ScreenObservationTest {
    @Before fun consent() { VisualImageCache.clear(); ImageRevocationAuthority.grantConsent() }
    @After fun revoke() { VisualImageCache.clear(); ImageRevocationAuthority.revokeConsent() }

    private class Port : ControlPort {
        val active = ControlSession().also { it.start() }
        var screenCalls = 0
        var strictCalls = 0
        var dispatches = 0
        var sourceAllowed = true
        var imageAvailable = true
        override fun session() = active
        override suspend fun capture() = ControlFrame("example", 1, "same", emptyList(), touchContext = "geometry")
        override suspend fun apps(filter: String) = JsonArray(emptyList())
        override fun visualDisclosureAllowed(frame: ControlFrame) = sourceAllowed
        override suspend fun visualScreenObserve(frame: ControlFrame): VisualTargetProof? {
            screenCalls++
            return if (!imageAvailable) null else proof()
        }
        override suspend fun visualDisplay(frame: ControlFrame): VisualTargetProof? { strictCalls++; return null }
        override fun visualFailure() = "display_isolated_screenshot_buffer_geometry_mismatch"
        fun proof() = VisualTargetProof(ScreenImage(100, 200, "image/jpeg", byteArrayOf(1), ScreenImageSource.MEDIA_PROJECTION),
            "pixels", TapBounds(0, 0, 100, 200), "geometry", System.currentTimeMillis(), scope = "screen_observe",
            sourceDisclosureAllowed = { sourceAllowed })
        override suspend fun perform(frame: ControlFrame, input: JsonObject, node: ControlNode?): ControlEffect {
            dispatches++; return ControlEffect(true, true, "unexpected")
        }
    }

    @Test fun explicitScreenObservationReturnsAnImageWithoutOwnershipOrActionClaims() = runBlocking {
        val port = Port()
        val control = GenericControl(port, { true })
        val result = control.observe(includeImage = true, imageScope = "screen_observe")
        assertTrue(result.summary, result.success)
        assertEquals(1, port.screenCalls)
        assertEquals(0, port.strictCalls)
        val visual = result.data.getValue("visual").jsonObject
        assertEquals(JsonPrimitive(true), visual["observation_only"])
        assertEquals(JsonPrimitive(false), visual["visual_actions_allowed"])
        assertEquals(JsonPrimitive(false), visual["owned_pixels_verified_against_isolated_windows"])
        assertEquals(JsonPrimitive(false), visual["masked_outside_owned_regions"])
        assertEquals(JsonPrimitive(0), visual["verified_pixel_count"])
        assertNotNull(VisualImageCache.imageFor(buildJsonObject { put("data", result.data) }))
    }

    @Test fun observationImageCannotMapOrDispatchAnyVisualAction() = runBlocking {
        for (action in listOf("visual_tap", "visual_long_press", "visual_drag", "visual_scroll", "visual_key")) {
            val port = Port()
            assertNull(port.proof().map(TapBounds(1, 1, 10, 10)))
            assertFalse(VisualActionPolicy.scopeAllowed(action, "screen_observe"))
            val control = GenericControl(port, { true })
            val observed = control.observe(includeImage = true, imageScope = "screen_observe")
            assertTrue(observed.summary, observed.success)
            val result = control.action(buildJsonObject {
                put("action", action); put("observation_id", observed.data.getValue("observation_id"))
                put("visual_id", observed.data.getValue("visual").jsonObject.getValue("visual_id"))
                put("visual_target_confirmed", true)
                put("image_rect", JsonArray(listOf(1, 1, 10, 10).map { JsonPrimitive(it) }))
            })
            assertFalse(result.success)
            assertEquals("visual_scope_mismatch", result.summary)
            assertEquals(0, port.dispatches)
        }
    }

    @Test fun strictMismatchNeverFallsBackToScreenObservation() = runBlocking {
        val port = Port()
        val result = GenericControl(port, { true }).observe(includeImage = true, imageScope = "display")
        assertFalse(result.success)
        assertEquals("display_isolated_screenshot_buffer_geometry_mismatch", result.summary)
        assertEquals(1, port.strictCalls)
        assertEquals(0, port.screenCalls)
    }

    @Test fun revocationAndFailedRecaptureInvalidateThePreviousSharedImage() = runBlocking {
        for (sourceRevocation in listOf(false, true)) {
            val port = Port()
            val control = GenericControl(port, { true })
            val observed = control.observe(includeImage = true, imageScope = "screen_observe")
            assertTrue(observed.summary, observed.success)
            val task = buildJsonObject { put("data", observed.data) }
            assertNotNull(VisualImageCache.imageFor(task))
            if (sourceRevocation) port.sourceAllowed = false else ImageRevocationAuthority.revokeConsent()
            assertNull(VisualImageCache.imageFor(task))
            ImageRevocationAuthority.grantConsent()
            port.sourceAllowed = true
            assertNull(VisualImageCache.imageFor(task))
            port.imageAvailable = false
            assertFalse(control.observe(includeImage = true, imageScope = "screen_observe").success)
            assertNull(VisualImageCache.imageFor(task))
        }
    }
}
