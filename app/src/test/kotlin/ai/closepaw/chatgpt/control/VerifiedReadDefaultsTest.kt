package ai.closepaw.chatgpt.control

import ai.closepaw.protocol.AppTier
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VerifiedReadDefaultsTest {
    private fun admitted(operation: String = "read", active: Boolean = true, bundled: AppTier? = null,
        userTier: AppTier? = null, ime: Boolean = false, browser: Boolean = false, web: Boolean = false) =
        VerifiedReadDefaults.permits(operation, active, bundled, userTier, ime, browser, web)

    @Test fun onlyLiveSelectedOwnersGainReadWithoutGrantingInput() {
        assertTrue(admitted(ime = true))
        assertTrue(admitted(browser = true, web = true))
        assertFalse(admitted()) // Unknown app, including a generic metadata/category claim.
        assertFalse(admitted(web = true)) // Web handler without the OS-selected browser.
        assertFalse(admitted(browser = true)) // Selection alone cannot prove a general web handler.
        assertFalse(admitted(active = false, ime = true))
        assertFalse(admitted(active = false, browser = true, web = true))
        assertFalse(admitted(operation = "interact", ime = true, browser = true, web = true))
        assertFalse(admitted(operation = "launch", ime = true))
        // Every evaluation uses current OS evidence, so loss of a default owner
        // revokes the same policy used by deferred image retrieval.
        assertFalse(admitted(browser = false, web = true))
        assertFalse(admitted(ime = false))
    }

    @Test fun explicitClassificationAndRuntimeDenialAlwaysWin() {
        for (tier in AppTier.entries) {
            assertFalse(admitted(bundled = tier, ime = true, browser = true, web = true))
            assertFalse(admitted(userTier = tier, ime = true, browser = true, web = true))
        }
        var disk: String? = null
        val settings = RuntimeControlSettings({ disk }, { disk = it })
        val defaultRead = admitted(ime = true)
        assertTrue(settings.access("example.ime", defaultRead, "read"))
        assertFalse(settings.access("example.ime", admitted(operation = "interact", ime = true), "interact"))
        settings.change(0, "apply", buildJsonObject {
            put("node_budget", 4096); put("output_limit", 50)
            put("apps", buildJsonObject { put("example.ime", "deny") })
        })
        assertFalse(settings.access("example.ime", defaultRead, "read"))
        assertFalse(settings.access("example.ime", false, "interact"))
    }
    @Test fun selectedOwnerLossRevokesDeferredImageWithoutConfigurationWrite() {
        for (usingIme in listOf(true, false)) {
            var selected = true
            val proof = VisualTargetProof(ai.closepaw.model.ScreenImage(1, 1, "image/jpeg", byteArrayOf(1),
                ai.closepaw.model.ScreenImageSource.ACCESSIBILITY_SCREENSHOT), "pixels", TapBounds(0,0,1,1),
                "window", System.currentTimeMillis())
            val result = buildJsonObject { put("data", buildJsonObject { put("visual", proof.metadata()) }) }
            VisualImageCache.put(proof) { admitted(ime = usingIme && selected,
                browser = !usingIme && selected, web = !usingIme) }
            assertNotNull(VisualImageCache.imageFor(result))
            selected = false
            assertNull(VisualImageCache.imageFor(result))
            selected = true
            assertNull(VisualImageCache.imageFor(result)) // A revoked image never resurrects.
        }
    }

}
