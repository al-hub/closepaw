package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class ControlWindowPolicyTest {
    private val host = ControlWindow(10, 0, 3, true, true, true, "example.app")
    private val popup = ControlWindow(11, 0, 5, true, false, false, "example.app", parent = 10)
    @Test fun aNonFocusedOwnedPopupMustReplaceTheUnderlyingActionSurface() {
        val result = ControlWindowPolicy.select(10, "example.app", listOf(host, popup))
        assertNull(result.reason)
        assertEquals("Visible owned popup must not be silently omitted", 11, result.target)
        assertEquals(listOf(10, 11), result.members)
    }
    @Test fun ordinaryForegroundRemainsReadable() {
        val result = ControlWindowPolicy.select(10, "example.app", listOf(host))
        assertNull(result.reason); assertEquals(10, result.target)
    }
    @Test fun missingOrUnfocusedForegroundIsRejected() {
        assertNotNull(ControlWindowPolicy.select(10, "example.app", emptyList()).reason)
        assertNotNull(ControlWindowPolicy.select(10, "example.app", listOf(host.copy(focused = false))).reason)
    }
    @Test fun anchorOwnershipAlsoIncludesANonFocusedPopup() {
        val result = ControlWindowPolicy.select(10, "example.app", listOf(host, popup.copy(parent = null, anchor = 10)))
        assertNull(result.reason); assertEquals(11, result.target)
    }
    @Test fun focusedPopupIncludesItsBackgroundHostForSecurityAndIdentity() {
        val result = ControlWindowPolicy.select(11, "example.app", listOf(host.copy(active = false, focused = false),
            popup.copy(active = true, focused = true)))
        assertNull(result.reason); assertEquals(11, result.target); assertEquals(listOf(10, 11), result.members)
    }
    @Test fun packageCoOccurrenceAndForeignCoveringWindowsNeverEstablishOwnership() {
        for (bad in listOf(popup.copy(parent = null), popup.copy(packageName = "foreign.app"),
            popup.copy(parent = 99), popup.copy(hasRoot = false), popup.copy(pictureInPicture = true)))
            assertNotNull(ControlWindowPolicy.select(10, "example.app", listOf(host, bad)).reason)
    }
    @Test fun conflictingOwnersCyclesAndAmbiguousLayersAreRejected() {
        for (bad in listOf(popup.copy(parent = 10, anchor = 99), popup.copy(parent = 11), popup.copy(layer = 3)))
            assertNotNull(ControlWindowPolicy.select(10, "example.app", listOf(host, bad)).reason)
        assertNotNull(ControlWindowPolicy.select(11, "example.app", listOf(host.copy(parent = 11),
            popup.copy(active = true, focused = true))).reason)
    }
    @Test fun keyboardAndOtherDisplaysAreNeverActionSurfaces() {
        val ime = popup.copy(application = false, packageName = "keyboard", parent = null)
        val otherDisplay = popup.copy(id = 12, display = 1, packageName = "foreign.app", parent = null)
        val result = ControlWindowPolicy.select(10, "example.app", listOf(host, ime, otherDisplay))
        assertNull(result.reason); assertEquals(listOf(10), result.members)
        assertNotNull(ControlWindowPolicy.select(11, "example.app", listOf(host.copy(display = 1),
            popup.copy(active = true, focused = true))).reason)
    }
    @Test fun nestedOwnedMenusAreOrderedAndBounded() {
        val second = popup.copy(id = 12, layer = 6, parent = 11)
        val third = popup.copy(id = 13, layer = 7, parent = 12)
        val fourth = popup.copy(id = 14, layer = 8, parent = 13)
        val result = ControlWindowPolicy.select(10, "example.app", listOf(second, host, third, popup))
        assertNull(result.reason); assertEquals(13, result.target); assertEquals(listOf(10, 11, 12, 13), result.members)
        assertEquals("owned_window_limit", ControlWindowPolicy.select(10, "example.app", listOf(host, popup, second, third, fourth)).reason)
    }
    @Test fun duplicateOrUnboundedWindowListsFailClosed() {
        assertNotNull(ControlWindowPolicy.select(10, "example.app", listOf(host, host)).reason)
        assertNotNull(ControlWindowPolicy.select(10, "example.app", (0..64).map { host.copy(id = it) }).reason)
    }

    private fun chromeFrame(
        snapshot: ControlWindowSnapshot?,
        keyboard: ControlKeyboard? = null,
        windowContext: String? = null,
    ): ControlFrame = ControlFrame(
        packageName = "com.android.chrome",
        windowId = 13300,
        fingerprint = "fp",
        nodes = emptyList(),
        foregroundWindowId = 13300,
        windowContext = windowContext ?: snapshot?.windowContext(keyboard).orEmpty(),
        configurationRevision = 139L,
        keyboard = keyboard,
        windowSnapshot = snapshot,
    )

    @Test fun continuityAllowsIdenticalBoundsAndProvableImeOcclusionRestorationOnly() {
        val imeKb = ControlKeyboard(13301, "com.samsung.android.honeyboard", "samsung/.HoneyBoard")
        val chromeWin = ControlWindow(13300, 0, 0, true, true, true, "com.android.chrome")
        val imeWin = ControlWindow(13301, 0, 1, false, false, false, "", hasRoot = false)
        val statusWin = ControlWindow(500, 0, 2, false, false, false, "com.android.systemui", hasRoot = true)
        val navWin = ControlWindow(501, 0, 3, false, false, false, "com.android.systemui", hasRoot = true)

        val imeRect = ControlWindowRect(0, 1450, 1080, 2214)
        val statusRect = ControlWindowRect(0, 0, 1080, 90)
        val navRect = ControlWindowRect(0, 2214, 1080, 2340)

        // 1) Identical Chrome bounds (edge-to-edge) + AOSP dense -1 layer compaction above IME -> ALLOWED
        val beforeEdge = ControlWindowSnapshot(13300, 13300, 4, listOf(
            ControlWindowRecord(statusWin, 3, statusRect),
            ControlWindowRecord(navWin, 3, navRect),
            ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 0, 1080, 2340)),
            ControlWindowRecord(imeWin, ControlWindowRecord.TYPE_INPUT_METHOD, imeRect),
        ))
        val afterEdge = ControlWindowSnapshot(13300, 13300, 3, listOf(
            ControlWindowRecord(statusWin.copy(layer = 1), 3, statusRect),
            ControlWindowRecord(navWin.copy(layer = 2), 3, navRect),
            ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 0, 1080, 2340)),
        ))
        assertNull(ControlWindowContinuity.imeDismissalRefusal(
            chromeFrame(beforeEdge, imeKb), chromeFrame(afterEdge, null), 13301, "com.android.chrome",
        ))

        // 2) Provable IME-occlusion vertical restoration: Chrome [0,0,1080,1450] -> [0,0,1080,2214] within IME [0,1450,1080,2214] -> ALLOWED
        val beforeClipped = ControlWindowSnapshot(13300, 13300, 4, listOf(
            ControlWindowRecord(statusWin, 3, statusRect),
            ControlWindowRecord(navWin, 3, navRect),
            ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 0, 1080, 1450)),
            ControlWindowRecord(imeWin, ControlWindowRecord.TYPE_INPUT_METHOD, imeRect),
        ))
        val afterRestored = ControlWindowSnapshot(13300, 13300, 3, listOf(
            ControlWindowRecord(statusWin.copy(layer = 1), 3, statusRect),
            ControlWindowRecord(navWin.copy(layer = 2), 3, navRect),
            ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 0, 1080, 2214)),
        ))
        assertNull(ControlWindowContinuity.imeDismissalRefusal(
            chromeFrame(beforeClipped, imeKb), chromeFrame(afterRestored, null), 13301, "com.android.chrome",
        ))

        // 3) Counterexamples: PiP, window type, packageName, arbitrary Chrome bounds, other non-IME bounds,
        //    layer reorder, signature mismatch, missing/truncated snapshot -> ALL REFUSED!
        val afterPip = ControlWindowSnapshot(13300, 13300, 3, listOf(
            ControlWindowRecord(statusWin.copy(layer = 1), 3, statusRect),
            ControlWindowRecord(navWin.copy(layer = 2), 3, navRect),
            ControlWindowRecord(chromeWin.copy(pictureInPicture = true), ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 0, 1080, 2214)),
        ))
        assertEquals("chrome_pip_detected", ControlWindowContinuity.imeDismissalRefusal(
            chromeFrame(beforeClipped, imeKb), chromeFrame(afterPip, null), 13301, "com.android.chrome",
        ))

        val afterTypeDrift = ControlWindowSnapshot(13300, 13300, 3, listOf(
            ControlWindowRecord(statusWin.copy(layer = 1), 4, statusRect), // TYPE_SYSTEM -> TYPE_ACCESSIBILITY_OVERLAY
            ControlWindowRecord(navWin.copy(layer = 2), 3, navRect),
            ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 0, 1080, 2214)),
        ))
        assertEquals("window_type_changed", ControlWindowContinuity.imeDismissalRefusal(
            chromeFrame(beforeClipped, imeKb), chromeFrame(afterTypeDrift, null), 13301, "com.android.chrome",
        ))

        val afterPkgDrift = ControlWindowSnapshot(13300, 13300, 3, listOf(
            ControlWindowRecord(statusWin.copy(layer = 1), 3, statusRect),
            ControlWindowRecord(navWin.copy(layer = 2), 3, navRect),
            ControlWindowRecord(chromeWin.copy(packageName = "com.evil.browser"), ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 0, 1080, 2214)),
        ))
        assertEquals("window_package_changed", ControlWindowContinuity.imeDismissalRefusal(
            chromeFrame(beforeClipped, imeKb), chromeFrame(afterPkgDrift, null), 13301, "com.android.chrome",
        ))

        // Arbitrary Chrome split-screen / horizontal resize / expansion past IME bottom -> REFUSED
        for (badRect in listOf(
            ControlWindowRect(0, 0, 540, 2214),     // right edge shrunk
            ControlWindowRect(0, 90, 1080, 2214),   // top edge shifted
            ControlWindowRect(0, 0, 1080, 1200),    // shrunk
            ControlWindowRect(0, 0, 1080, 2340),    // expanded past IME bottom (2214)
        )) {
            val afterBadChromeBounds = ControlWindowSnapshot(13300, 13300, 3, listOf(
                ControlWindowRecord(statusWin.copy(layer = 1), 3, statusRect),
                ControlWindowRecord(navWin.copy(layer = 2), 3, navRect),
                ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, badRect),
            ))
            assertEquals("chrome_window_bounds_changed", ControlWindowContinuity.imeDismissalRefusal(
                chromeFrame(beforeClipped, imeKb), chromeFrame(afterBadChromeBounds, null), 13301, "com.android.chrome",
            ))
        }

        // Other non-IME window bounds drift -> REFUSED
        val afterStatusBoundsDrift = ControlWindowSnapshot(13300, 13300, 3, listOf(
            ControlWindowRecord(statusWin.copy(layer = 1), 3, ControlWindowRect(0, 0, 1080, 400)),
            ControlWindowRecord(navWin.copy(layer = 2), 3, navRect),
            ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 0, 1080, 2214)),
        ))
        assertEquals("non_ime_window_bounds_changed", ControlWindowContinuity.imeDismissalRefusal(
            chromeFrame(beforeClipped, imeKb), chromeFrame(afterStatusBoundsDrift, null), 13301, "com.android.chrome",
        ))

        // Cryptographic signature mismatch (tampered/stale snapshot) -> REFUSED
        assertEquals("window_snapshot_signature_mismatch", ControlWindowContinuity.imeDismissalRefusal(
            chromeFrame(beforeClipped, imeKb), chromeFrame(afterRestored, null, windowContext = "different-hash"), 13301, "com.android.chrome",
        ))

        // Missing or truncated snapshot -> REFUSED
        assertEquals("window_snapshot_missing", ControlWindowContinuity.imeDismissalRefusal(
            chromeFrame(null, imeKb, windowContext = "ctx"), chromeFrame(afterRestored, null), 13301, "com.android.chrome",
        ))
        assertEquals("window_snapshot_truncated", ControlWindowContinuity.imeDismissalRefusal(
            chromeFrame(beforeClipped.copy(totalWindowCount = 65), imeKb), chromeFrame(afterRestored, null), 13301, "com.android.chrome",
        ))

        // 4) Non-app owner / overlay checks:
        // 4a) Root-less non-overlapping TYPE_SYSTEM bar (e.g. bottom gesture bar below Chrome) -> ALLOWED (no false positive)
        val rootlessNavWin = ControlWindow(501, 0, 3, false, false, false, "", hasRoot = false)
        val beforeRootlessNonOverlap = ControlWindowSnapshot(13300, 13300, 4, listOf(
            ControlWindowRecord(statusWin, ControlWindowRecord.TYPE_SYSTEM, statusRect),
            ControlWindowRecord(rootlessNavWin, ControlWindowRecord.TYPE_SYSTEM, navRect),
            ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 90, 1080, 1450)),
            ControlWindowRecord(imeWin, ControlWindowRecord.TYPE_INPUT_METHOD, imeRect),
        ))
        val afterRootlessNonOverlap = ControlWindowSnapshot(13300, 13300, 3, listOf(
            ControlWindowRecord(statusWin.copy(layer = 1), ControlWindowRecord.TYPE_SYSTEM, statusRect),
            ControlWindowRecord(rootlessNavWin.copy(layer = 2), ControlWindowRecord.TYPE_SYSTEM, navRect),
            ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 90, 1080, 2214)),
        ))
        assertNull(ControlWindowContinuity.imeDismissalRefusal(
            chromeFrame(beforeRootlessNonOverlap, imeKb), chromeFrame(afterRootlessNonOverlap, null), 13301, "com.android.chrome",
        ))

        // 4b) Root-less TYPE_SYSTEM window overlapping Chrome -> REFUSED
        val rootlessOverlapWin = ControlWindow(500, 0, 2, false, false, false, "", hasRoot = false)
        val beforeRootlessOverlap = ControlWindowSnapshot(13300, 13300, 3, listOf(
            ControlWindowRecord(rootlessOverlapWin, ControlWindowRecord.TYPE_SYSTEM, statusRect),
            ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 0, 1080, 1450)),
            ControlWindowRecord(imeWin, ControlWindowRecord.TYPE_INPUT_METHOD, imeRect),
        ))
        val afterRootlessOverlap = ControlWindowSnapshot(13300, 13300, 2, listOf(
            ControlWindowRecord(rootlessOverlapWin.copy(layer = 1), ControlWindowRecord.TYPE_SYSTEM, statusRect),
            ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 0, 1080, 2214)),
        ))
        assertEquals("unverified_overlapping_system_window", ControlWindowContinuity.imeDismissalRefusal(
            chromeFrame(beforeRootlessOverlap, imeKb), chromeFrame(afterRootlessOverlap, null), 13301, "com.android.chrome",
        ))

        // 4c) Third-party TYPE_SYSTEM owner (static or swapped) -> REFUSED
        val thirdPartySysA = ControlWindow(500, 0, 2, false, false, false, "com.overlay.a", hasRoot = true)
        val thirdPartySysB = ControlWindow(500, 0, 1, false, false, false, "com.overlay.b", hasRoot = true)
        val beforeThirdParty = ControlWindowSnapshot(13300, 13300, 3, listOf(
            ControlWindowRecord(thirdPartySysA, ControlWindowRecord.TYPE_SYSTEM, statusRect),
            ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 90, 1080, 1450)),
            ControlWindowRecord(imeWin, ControlWindowRecord.TYPE_INPUT_METHOD, imeRect),
        ))
        val afterThirdPartySame = ControlWindowSnapshot(13300, 13300, 2, listOf(
            ControlWindowRecord(thirdPartySysA.copy(layer = 1), ControlWindowRecord.TYPE_SYSTEM, statusRect),
            ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 90, 1080, 2214)),
        ))
        val afterThirdPartySwapped = ControlWindowSnapshot(13300, 13300, 2, listOf(
            ControlWindowRecord(thirdPartySysB, ControlWindowRecord.TYPE_SYSTEM, statusRect),
            ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 90, 1080, 2214)),
        ))
        assertEquals("untrusted_non_app_window_owner", ControlWindowContinuity.imeDismissalRefusal(
            chromeFrame(beforeThirdParty, imeKb), chromeFrame(afterThirdPartySame, null), 13301, "com.android.chrome",
        ))
        assertEquals("window_package_changed", ControlWindowContinuity.imeDismissalRefusal(
            chromeFrame(beforeThirdParty, imeKb), chromeFrame(afterThirdPartySwapped, null), 13301, "com.android.chrome",
        ))

        // 4d) Unowned TYPE_ACCESSIBILITY_OVERLAY (even non-overlapping) -> REFUSED on both IME dismissal and Back #2 exact check
        val accOverlay = ControlWindow(500, 0, 2, false, false, false, "", hasRoot = false)
        val beforeAccOverlay = ControlWindowSnapshot(13300, 13300, 3, listOf(
            ControlWindowRecord(accOverlay, ControlWindowRecord.TYPE_ACCESSIBILITY_OVERLAY, statusRect),
            ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 90, 1080, 1450)),
            ControlWindowRecord(imeWin, ControlWindowRecord.TYPE_INPUT_METHOD, imeRect),
        ))
        val afterAccOverlay = ControlWindowSnapshot(13300, 13300, 2, listOf(
            ControlWindowRecord(accOverlay.copy(layer = 1), ControlWindowRecord.TYPE_ACCESSIBILITY_OVERLAY, statusRect),
            ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 90, 1080, 2214)),
        ))
        assertEquals("unverified_overlay_present", ControlWindowContinuity.imeDismissalRefusal(
            chromeFrame(beforeAccOverlay, imeKb), chromeFrame(afterAccOverlay, null), 13301, "com.android.chrome",
        ))
        assertEquals("unverified_overlay_present", ControlWindowContinuity.exactSnapshotRefusal(
            chromeFrame(afterAccOverlay, null), chromeFrame(afterAccOverlay, null), "com.android.chrome",
        ))

        // 4e) TYPE_SYSTEM with live root (hasRoot=true) but blank packageName="" (even non-overlapping) -> REFUSED on both IME dismissal and Back #2 exact check
        val liveRootBlankOwner = ControlWindow(500, 0, 2, false, false, false, "", hasRoot = true)
        val beforeLiveRootBlank = ControlWindowSnapshot(13300, 13300, 3, listOf(
            ControlWindowRecord(liveRootBlankOwner, ControlWindowRecord.TYPE_SYSTEM, statusRect),
            ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 90, 1080, 1450)),
            ControlWindowRecord(imeWin, ControlWindowRecord.TYPE_INPUT_METHOD, imeRect),
        ))
        val afterLiveRootBlank = ControlWindowSnapshot(13300, 13300, 2, listOf(
            ControlWindowRecord(liveRootBlankOwner.copy(layer = 1), ControlWindowRecord.TYPE_SYSTEM, statusRect),
            ControlWindowRecord(chromeWin, ControlWindowRecord.TYPE_APPLICATION, ControlWindowRect(0, 90, 1080, 2214)),
        ))
        assertEquals("unverified_non_app_window_owner", ControlWindowContinuity.imeDismissalRefusal(
            chromeFrame(beforeLiveRootBlank, imeKb), chromeFrame(afterLiveRootBlank, null), 13301, "com.android.chrome",
        ))
        assertEquals("unverified_non_app_window_owner", ControlWindowContinuity.exactSnapshotRefusal(
            chromeFrame(afterLiveRootBlank, null), chromeFrame(afterLiveRootBlank, null), "com.android.chrome",
        ))
    }
}
