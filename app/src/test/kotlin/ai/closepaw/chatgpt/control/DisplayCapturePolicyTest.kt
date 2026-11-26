package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class DisplayCapturePolicyTest {
    @Test fun onlyCompleteOwnedDefaultDisplaySurfaceIsAdmitted() {
        assertNull(DisplayCapturePolicy.refusal(true,0,0,listOf(1,2),setOf(1,2)))
        assertEquals("display_unowned_window",DisplayCapturePolicy.refusal(true,0,0,listOf(1,2,3),setOf(1,2)))
        assertEquals("display_geometry_unverified",DisplayCapturePolicy.refusal(true,1,0,listOf(1),setOf(1)))
        assertEquals("display_geometry_unverified",DisplayCapturePolicy.refusal(false,0,0,listOf(1),setOf(1)))
        assertEquals("display_unowned_window",DisplayCapturePolicy.refusal(true,0,0,listOf(1,1),setOf(1)))
    }
    @Test fun diagnosticsExposeOnlyBoundedNativeCodes() {
        assertEquals("screenshot_secure_window",VisualCaptureFailure.native(6))
        assertEquals("screenshot_invalid_window",VisualCaptureFailure.native(5))
        assertEquals("screenshot_native_failure",VisualCaptureFailure.native(8123))
    }
    @Test fun displayProofPreservesWholePixelCoordinateOriginAndRevocation() {
        val p=VisualTargetProof(ai.closepaw.model.ScreenImage(432,960,"image/jpeg",byteArrayOf(1),ai.closepaw.model.ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
            "hash",TapBounds(0,0,1080,2400),"rotation0",System.currentTimeMillis(),scope="display",sourceDisplayId=0,rotation=0,disclosedRegions=listOf(TapBounds(0,0,1080,2400)))
        assertEquals(TapBounds(100,2000,200,2100),p.map(TapBounds(40,800,80,840)))
        assertNull(p.map(TapBounds(0,0,433,960)))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(0),p.metadata()["source_display_id"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(1080),p.metadata()["source_width"])
    }
    @Test fun transientWindowCannotBeHiddenByMatchingEndpointSnapshots() {
        val signal=ControlChangeSignal(); val before=signal.version()
        assertNull(DisplayCapturePolicy.interveningChange(before,signal.version()))
        signal.changed(); signal.changed() // overlay appeared then disappeared
        assertEquals("display_window_changed_during_capture",DisplayCapturePolicy.interveningChange(before,signal.version()))
    }
    @Test fun displayKeyKeepsSeparateImeProvenanceAtNativeDispatch() {
        val k=ControlKeyboard(6,"ime","ime/.Keyboard")
        val p=VisualTargetProof(ai.closepaw.model.ScreenImage(432,960,"image/jpeg",byteArrayOf(1),ai.closepaw.model.ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
            "hash",TapBounds(0,0,1080,2400),"rotation0",System.currentTimeMillis(),scope="display",sourceDisplayId=0,
            sourceImeWindowId=6,imeBounds="0 1600 1080 2400",displayGeneration=0)
        assertNull(VisualKeyboardPolicy.proofOwnerRefusal(p,k))
        assertNotNull(VisualKeyboardPolicy.proofOwnerRefusal(p,k.copy(windowId=7)))
        assertNotNull(VisualKeyboardPolicy.proofOwnerRefusal(p.copy(sourceDisplayId=1),k))
        assertNotNull(VisualKeyboardPolicy.proofOwnerRefusal(p.copy(displayGeneration=-1),k))
        assertNotNull(VisualKeyboardPolicy.proofOwnerRefusal(p.copy(scope="app"),k))
        val nativeKeyboard = p.copy(scope="keyboard",sourceWindowId=6,
            disclosedRegions=listOf(TapBounds(0,1600,1080,2400)))
        assertNull(VisualKeyboardPolicy.proofOwnerRefusal(nativeKeyboard,k))
        assertNotNull(VisualKeyboardPolicy.proofOwnerRefusal(nativeKeyboard.copy(disclosedRegions=emptyList()),k))
        assertNotNull(VisualKeyboardPolicy.proofOwnerRefusal(nativeKeyboard.copy(sourceImeWindowId=7),k))
        assertNull(VisualKeyboardPolicy.proofOwnerRefusal(p.copy(scope="keyboard",sourceWindowId=6,sourceDisplayId=-1),k))
    }

    @Test fun sharedRecoveryPolicyAllowsOnlyBoundedPreExecutionTransientRefusals() {
        assertEquals(3, DisplayObservationRecoveryPolicy.MAX_READ_ONLY_RECOVERIES)
        // Recoverable read-only observation races within budget [0, 1, 2]
        for (reason in listOf("display_window_changed_during_capture", "visual_post_screen_changed")) {
            assertTrue(DisplayObservationRecoveryPolicy.mayRecoverPreExecution(reason, 0, false, false, true, observationOnly = true))
            assertTrue(DisplayObservationRecoveryPolicy.mayRecoverPreExecution(reason, 2, false, false, true, observationOnly = true))
            assertFalse(DisplayObservationRecoveryPolicy.mayRecoverPreExecution(reason, 3, false, false, true, observationOnly = true))
        }
        // Pre-dispatch no-effect display/tree invalidation
        assertTrue(DisplayObservationRecoveryPolicy.mayRecoverPreExecution(
            "display_window_changed_since_observation", 0, false, false, true))
        assertTrue(DisplayObservationRecoveryPolicy.mayRecoverPreExecution(
            "screen_changed", 0, false, false, true))
        assertTrue(DisplayObservationRecoveryPolicy.mayRecoverPreExecution(
            VISUAL_SOURCE_REFUSED, 0, false, false, true,
            visualSourceStage = VisualSourceStage.NATIVE_ACTION_VALIDATION.code,
            visualSourceCondition = VisualSourceCondition.DISPLAY_WINDOW_CHANGED.code))

        // Hard fail-closed: attempted action, UNKNOWN, lost authority, wrong stage, overlay, rotation, consent, secure window
        assertFalse(DisplayObservationRecoveryPolicy.mayRecoverPreExecution(
            "display_window_changed_during_capture", 0, actionAttempted = true, outcomeUnknown = false, authorityActive = true))
        assertFalse(DisplayObservationRecoveryPolicy.mayRecoverPreExecution(
            "display_window_changed_during_capture", 0, actionAttempted = false, outcomeUnknown = true, authorityActive = true))
        assertFalse(DisplayObservationRecoveryPolicy.mayRecoverPreExecution(
            "display_window_changed_during_capture", 0, actionAttempted = false, outcomeUnknown = false, authorityActive = false))
        assertFalse(DisplayObservationRecoveryPolicy.mayRecoverPreExecution(
            VISUAL_SOURCE_REFUSED, 0, false, false, true,
            visualSourceStage = VisualSourceStage.PRE_GESTURE.code,
            visualSourceCondition = VisualSourceCondition.DISPLAY_WINDOW_CHANGED.code))
        assertFalse(DisplayObservationRecoveryPolicy.mayRecoverPreExecution(
            VISUAL_SOURCE_REFUSED, 0, false, false, true,
            visualSourceStage = VisualSourceStage.NATIVE_ACTION_VALIDATION.code,
            visualSourceCondition = VisualSourceCondition.IMAGE_CONSENT_CHANGED.code))
        for (nonRecoverable in listOf(
            "display_surface_unverified", "display_unowned_window", "display_geometry_unverified",
            "display_post_geometry_changed", "screen_share_revoked", "accessibility_permission_missing",
            "secure_window", "visual_sensitive_editor", "visual_revalidation_failed",
            "touch_target_clipped_or_covered", "keyboard_key_changed",
        )) {
            assertFalse(nonRecoverable, DisplayObservationRecoveryPolicy.mayRecoverPreExecution(
                nonRecoverable, 0, false, false, true))
        }
    }

    @Test fun sharedRecertificationRejectsTargetOverlayRotationImePermissionAndValueChanges() {
        val ime = ControlKeyboard(20, "com.samsung.android.honeyboard", "com.samsung.android.honeyboard/.HoneyBoard")
        val ed = ControlNode(
            id = 1, resourceId = "com.android.chrome:id/url_bar", className = "android.widget.EditText",
            editable = true, focused = true, focusable = true, enabled = true,
            text = "", empty = true, selectionStart = 0, selectionEnd = 0,
            sourceWindowId = 10, sourcePackage = "com.android.chrome", bounds = "0 0 800 100",
        )
        val base = ControlFrame(
            packageName = "com.android.chrome", windowId = 10, foregroundWindowId = 10,
            fingerprint = "fp-1", nodes = listOf(ed), touchContext = "rot0-clean",
            windowContext = "win-ctx-1", configurationRevision = 5L,
            keyboard = ime, inputTargetId = ed.id, inputTarget = ed,
        )
        assertNull(DisplayObservationRecoveryPolicy.recertificationRefusal(base, base.copy(fingerprint = "fp-2")))
        assertEquals("target_package_changed", DisplayObservationRecoveryPolicy.recertificationRefusal(base, base.copy(packageName = "com.other.app")))
        assertEquals("target_window_changed", DisplayObservationRecoveryPolicy.recertificationRefusal(base, base.copy(windowId = 11)))
        assertEquals("target_window_changed", DisplayObservationRecoveryPolicy.recertificationRefusal(base, base.copy(foregroundWindowId = 99)))
        assertEquals("configuration_revision_changed", DisplayObservationRecoveryPolicy.recertificationRefusal(base, base.copy(configurationRevision = 6L)))
        assertEquals("window_context_changed", DisplayObservationRecoveryPolicy.recertificationRefusal(base, base.copy(windowContext = "win-ctx-overlay")))
        assertEquals("touch_context_or_rotation_changed", DisplayObservationRecoveryPolicy.recertificationRefusal(base, base.copy(touchContext = "rot1-landscape")))
        assertEquals("touch_context_or_rotation_changed", DisplayObservationRecoveryPolicy.recertificationRefusal(base, base.copy(touchContext = "")))
        assertEquals("keyboard_changed_or_unauthorized", DisplayObservationRecoveryPolicy.recertificationRefusal(base, base.copy(keyboard = ime.copy(interactAllowed = false))))
        assertEquals("keyboard_changed_or_unauthorized", DisplayObservationRecoveryPolicy.recertificationRefusal(base, base.copy(keyboard = null)))
        val otherEd = ed.copy(resourceId = "com.android.chrome:id/other_box")
        assertEquals("editor_identity_changed", DisplayObservationRecoveryPolicy.recertificationRefusal(base, base.copy(nodes = listOf(otherEd), inputTarget = otherEd)))
        val typedEd = ed.copy(text = "a", empty = false, selectionStart = 1, selectionEnd = 1)
        assertEquals("editor_value_or_caret_changed", DisplayObservationRecoveryPolicy.recertificationRefusal(base, base.copy(nodes = listOf(typedEd), inputTarget = typedEd)))

        // Fresh display proof requires new ID, strictly newer generation, keyboard scope, and active disclosure
        val oldProof = VisualTargetProof(
            ai.closepaw.model.ScreenImage(432, 960, "image/jpeg", byteArrayOf(1), ai.closepaw.model.ScreenImageSource.ACCESSIBILITY_SCREENSHOT),
            "h1", TapBounds(0, 0, 1080, 2400), "ctx", System.currentTimeMillis(),
            id = "vis-1", scope = "keyboard", sourceDisplayId = 0, displayGeneration = 5L,
        )
        assertTrue(DisplayObservationRecoveryPolicy.isFreshDisplayProof("vis-1", 5L, oldProof.copy(id = "vis-2", displayGeneration = 6L)))
        assertFalse(DisplayObservationRecoveryPolicy.isFreshDisplayProof("vis-1", 5L, oldProof.copy(id = "vis-1", displayGeneration = 6L)))
        assertFalse(DisplayObservationRecoveryPolicy.isFreshDisplayProof("vis-1", 5L, oldProof.copy(id = "vis-2", displayGeneration = 5L)))
        assertFalse(DisplayObservationRecoveryPolicy.isFreshDisplayProof("vis-1", 5L, oldProof.copy(id = "vis-2", displayGeneration = 6L, sourceDisclosureAllowed = { false })))
    }

}
