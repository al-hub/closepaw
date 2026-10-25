package ai.closepaw.qa

import ai.closepaw.platform.OwnedClipboardLease
import ai.closepaw.platform.NodeActionPerformer
import ai.closepaw.platform.ActionResult
import android.widget.EditText
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator-only native ClipboardManager contract, not another app's Paste acceptance. */
@RunWith(AndroidJUnit4::class)
class ClipboardProvenanceTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun nativeDirectAndPastePrimitivesInsertExactMixedText() {
        lateinit var editor: EditText
        compose.setContent { AndroidView(factory = { context -> EditText(context).also {
            editor = it; it.setSingleLine(true); it.isFocusableInTouchMode = true
            it.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
            it.showSoftInputOnFocus = false
        } }) }
        compose.waitForIdle()
        // Compose idle does not imply Android WM has granted input/clipboard focus.
        compose.waitUntil(timeoutMillis = 15_000) { compose.activity.hasWindowFocus() }
        compose.runOnIdle { assertTrue("Attached editor must own input focus", editor.requestFocusFromTouch()); assertTrue(editor.hasFocus()) }
        val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.waitForIdle(500, 5_000)
        compose.waitUntil(timeoutMillis = 15_000) {
            val root = automation.rootInActiveWindow
            val focused = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            try { focused?.isEditable == true && focused.isFocused }
            finally { if (focused !== root) focused?.recycle(); root?.recycle() }
        }
        val performer = NodeActionPerformer({ automation.rootInActiveWindow })
        val direct = runBlocking { performer.performSetTextOnFocused("한글 A1!", clear = true) }
        assertTrue(direct.toString(), direct is ActionResult.Success)
        compose.runOnIdle { assertEquals("한글 A1!", editor.text.toString()); editor.setText(""); editor.requestFocus() }
        automation.waitForIdle(500, 5_000)
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        compose.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("", "fixture-before"))
            val lease = OwnedClipboardLease.acquire(clipboard, "붙여넣기 B2?", true)
            assertNotNull(lease); assertTrue(lease!!.stage()); assertTrue(lease.ownsCurrent())
        }
        try {
            assertTrue(runBlocking { performer.canPasteOnFocused() })
            val paste = runBlocking { performer.performPasteOnFocused(true) }
            assertTrue(paste.toString(), paste is ActionResult.Success)
            automation.waitForIdle(500, 5_000)
            compose.runOnIdle { assertEquals("붙여넣기 B2?", editor.text.toString()) }
        } finally { compose.runOnIdle { clipboard.clearPrimaryClip() } }
    }
    @Test fun nativeLeaseRequiresConsentAndDetectsCompetingClipboardChange() {
        compose.setContent { Text("Clipboard provenance fixture") }
        compose.waitForIdle()
        // Compose idle does not imply Android WM has granted input/clipboard focus.
        compose.waitUntil(timeoutMillis = 15_000) { compose.activity.hasWindowFocus() }
        compose.runOnIdle {
            val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("", "fixture-before"))
            try {
                assertNull(OwnedClipboardLease.acquire(clipboard, "fixture-input", false))
                assertEquals("clipboard_replacement_authorization_required",
                    OwnedClipboardLease.inspectAndAcquire(clipboard, "fixture-input", false).refusal)
                assertEquals("fixture-before", clipboard.primaryClip!!.getItemAt(0).text.toString())
                val lease = OwnedClipboardLease.acquire(clipboard, "fixture-input", true)
                assertNotNull(lease); assertTrue(lease!!.stage()); assertTrue(lease.ownsCurrent())
                clipboard.setPrimaryClip(ClipData.newPlainText("", "fixture-competing"))
                assertFalse(lease.ownsCurrent())
                assertEquals("fixture-competing", clipboard.primaryClip!!.getItemAt(0).text.toString())
            } finally { clipboard.clearPrimaryClip() }
            val emptyOrDenied = OwnedClipboardLease.inspectAndAcquire(clipboard, "fixture-input", true)
            assertNull(emptyOrDenied.lease)
            assertEquals("clipboard_empty_or_read_restricted", emptyOrDenied.refusal)
            assertNull("Refusal must not stage clipboard text", clipboard.primaryClip)
        }
    }
}
