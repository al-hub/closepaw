package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ControlResultPolicyTest {
    private val node = ControlNode(1, resourceId = "switch", bounds = "0 0 50 50", checkable = true)
    private val before = ControlFrame("settings", 1, "before", listOf(node))
    @Test fun hostedLaunchNeedsPositiveCompleteApplicationIdentity() {
        val input = buildJsonObject { put("package_name", "gemini.app") }
        val host = before.copy(packageName = "google.host", fingerprint = "host")
        assertFalse(ControlResultPolicy.verify("open_app", before, host, null, input).confirmed)
        val verified = host.copy(verifiedApplicationPackage = "gemini.app")
        assertTrue(ControlResultPolicy.verify("open_app", before, verified, null, input).confirmed)
        assertFalse(ControlResultPolicy.verify("open_app", before, verified.copy(truncated = true), null, input).confirmed)
        assertFalse(ControlResultPolicy.verify("open_app", before, verified.copy(reason = "secure_window"), null, input).confirmed)
        assertFalse(verified.matchesApplication("another.app"))
    }
    @Test fun unrelatedScreenChangeDoesNotProveToggle() {
        val result = ControlResultPolicy.verify("click", before, before.copy(fingerprint = "clock tick"), node, buildJsonObject {})
        assertFalse(result.confirmed)
        assertEquals("checked_state_unconfirmed", result.reason)
    }
    @Test fun checkedStateAndExactAppMustMatch() {
        assertTrue(ControlResultPolicy.verify("tap", before, before.copy(nodes = listOf(node.copy(checked = true))), node, buildJsonObject {}).confirmed)
        assertFalse(ControlResultPolicy.verify("tap", before, before.copy(packageName = "other", nodes = listOf(node.copy(checked = true))), node, buildJsonObject {}).confirmed)
        val input = buildJsonObject { put("package_name", "gemini") }
        assertFalse(ControlResultPolicy.verify("open_app", before, before, null, input).confirmed)
        assertTrue(ControlResultPolicy.verify("open_app", before, before.copy(packageName = "gemini"), null, input).confirmed)
    }
    @Test fun ambiguousOrIncompleteTargetsCannotVerify() {
        val checked = node.copy(checked = true)
        for (after in listOf(before.copy(nodes = listOf(checked, checked)), before.copy(truncated = true), before.copy(reason = "device_locked")))
            assertFalse(ControlResultPolicy.verify("click", before, after, node, buildJsonObject {}).confirmed)
    }
    @Test fun appSearchDoesNotRevokeOtherExactSelectionAndNeverGrantsPolicy() {
        val selections = ControlAppSelections()
        selections.add(listOf("gemini")); selections.add(listOf("notes"))
        assertNull(selections.refusal("gemini", true))
        assertEquals("app_launch_blocked", selections.refusal("gemini", false))
        assertEquals("app_selection_required", selections.refusal("unknown", true))
        selections.clear()
        assertEquals("app_selection_required", selections.refusal("gemini", true))
    }
    @Test fun samsungLanguageAliasNeedsTwoChangedKeyPositionsAndPreservedEmptyCaret() {
        val ime = ControlKeyboard(4, "com.samsung.android.honeyboard",
            "com.samsung.android.honeyboard/.service.HoneyBoardService")
        val field = ControlNode(1, resourceId = "browser:id/url", className = "android.widget.EditText",
            bounds = "0 0 100 30", sourcePackage = "browser", sourceWindowId = 1, editable = true, focusable = true,
            focused = true, selectionStart = 0, selectionEnd = 0, keyboardCursorGeneration = 3)
        val alias = ControlNode(2, description = "영어(미국)로 변경", keyboardKey = "한/영", className = "android.view.ViewGroup",
            visualKeyChildCount = 1, sourcePackage = ime.packageName, sourceWindowId = ime.windowId, visualKeyCandidate = true)
        fun literal(id: Int, bounds: String, label: String) = ControlNode(id, text = label, bounds = bounds, keyboardKey = label,
            sourcePackage = ime.packageName, sourceWindowId = ime.windowId, visualKeyCandidate = true)
        val first = literal(3, "0 50 50 90", "ㅂ")
        val second = literal(4, "55 50 105 90", "ㅈ")
        val old = ControlFrame("browser", 1, "before", listOf(field, alias, first, second),
            keyboard = ime, inputTarget = field, inputTargetId = field.id)
        val q = first.copy(text = "q", keyboardKey = "q")
        val w = second.copy(text = "w", keyboardKey = "w")
        fun verify(post: ControlFrame) = ControlResultPolicy.verify("visual_key", old, post, alias, buildJsonObject {}).confirmed
        assertTrue(verify(old.copy(nodes = listOf(field, q, w))))
        assertFalse(verify(old.copy(nodes = listOf(field, q, second))))
        assertFalse(verify(old.copy(nodes = listOf(field, q, w), inputTarget = field.copy(selectionEnd = -1))))
        val english = old.copy(nodes = listOf(field, q, w))
        val reverse = alias.copy(description = "한국어로 변경")
        fun reverseVerdict(post: ControlFrame) = ControlResultPolicy.verify("visual_key", english, post, reverse, buildJsonObject {}).confirmed
        assertTrue(reverseVerdict(old))
        assertFalse(reverseVerdict(english.copy(nodes = listOf(field, q.copy(text = "Q"), w.copy(text = "W")))))
        assertFalse(reverseVerdict(english.copy(nodes = listOf(field, q.copy(text = "1"), w.copy(text = "2")))))
    }

    @Test fun visualKeyRequiresExactTextCaretAndSameUniqueFieldThenBackspaceRestoresIt() {
        val ime = ControlKeyboard(2, "ime", "ime/.Keyboard")
        val empty = ControlNode(7, resourceId = "browser:id/url", className = "android.widget.EditText",
            bounds = "20 10 100 30", sourcePackage = "browser", sourceWindowId = 1, focusable = true,
            focused = true, editable = true, selectionStart = 0, selectionEnd = 0, keyboardCursorGeneration = 4)
        val old = ControlFrame("browser", 1, "before", listOf(empty), keyboard = ime, inputTarget = empty, inputTargetId = empty.id)
        val a = empty.copy(bounds = "20 10 85 30", text = "a", selectionStart = 1, selectionEnd = 1, keyboardCursorGeneration = null)
        val next = old.copy(nodes = listOf(a), inputTarget = a)
        val imageKey = ControlNode(8, keyboardImageCandidate = true)
        val input = buildJsonObject { put("image_key_label", "a") }
        assertTrue(ControlResultPolicy.verify("visual_key", old, next, imageKey, input).confirmed)
        for (wrong in listOf(a.copy(text = "aa"), a.copy(selectionEnd = 0), a.copy(resourceId = "browser:id/other"))) {
            assertFalse(ControlResultPolicy.verify("visual_key", old, next.copy(nodes = listOf(wrong), inputTarget = wrong), imageKey, input).confirmed)
        }
        assertFalse(ControlResultPolicy.verify("visual_key", old, next.copy(nodes = listOf(a, a.copy(id = 9))), imageKey, input).confirmed)
        assertFalse(ControlResultPolicy.verify("visual_key", old, next, imageKey, buildJsonObject { put("image_key_label", "↵") }).confirmed)
        val withSpace = a.copy(text = "a ", selectionStart = 2, selectionEnd = 2)
        val autoCompleted = a.copy(text = "a suggested.com", selectionStart = 2, selectionEnd = 15)
        val space = buildJsonObject { put("image_key_label", "Space") }
        assertTrue(ControlResultPolicy.verify("visual_key", next, old.copy(nodes = listOf(withSpace), inputTarget = withSpace), imageKey, space).confirmed)
        assertFalse(ControlResultPolicy.verify("visual_key", next, old.copy(nodes = listOf(autoCompleted), inputTarget = autoCompleted), imageKey, space).confirmed)
        val backspace = ControlNode(9, text = "Backspace", keyboardKey = "Backspace")
        assertTrue(ControlResultPolicy.verify("visual_key", next, old, backspace, buildJsonObject {}).confirmed)
    }
    @Test fun exactInputSurvivesUniqueSemanticFieldResizeOnlyAfterDispatch() {
        val field = ControlNode(7, resourceId = "browser:id/url", className = "android.widget.EditText",
            bounds = "224 127 1076 323", sourceWindowId = 1, sourcePackage = "browser", editable = true,
            focused = true, setText = true, selectionStart = 0, selectionEnd = 0)
        val old = ControlFrame("browser", 1, "before", listOf(field))
        val changed = field.copy(text = "한글 A1!", bounds = "224 127 1244 323")
        val next = old.copy(nodes = listOf(changed), fingerprint = "after")
        val input = buildJsonObject { put("text", "한글 A1!"); put("method", "direct"); put("mode", "empty") }
        fun verify(frame: ControlFrame) = ControlResultPolicy.verify("input", old, frame, field, input).confirmed
        assertTrue(verify(next))
        for (bad in listOf(next.copy(packageName = "other"), next.copy(windowId = 2), next.copy(truncated = true),
            next.copy(nodes = listOf(changed, changed.copy(bounds = field.bounds))),
            next.copy(nodes = listOf(changed.copy(focused = false))), next.copy(nodes = listOf(changed.copy(password = true))),
            next.copy(nodes = listOf(changed.copy(enabled = false))), next.copy(nodes = listOf(changed.copy(text = "한글 A1!!"))),
            next.copy(nodes = listOf(changed.copy(sourceWindowId = 2))), next.copy(nodes = listOf(changed.copy(sourcePackage = "other")))))
            assertFalse(verify(bad))
        assertFalse(ControlResultPolicy.verify("input", old.copy(nodes = listOf(field, field.copy(bounds = "elsewhere"))), next, field, input).confirmed)
        assertFalse(ControlResultPolicy.verify("input", old.copy(nodes = listOf(field.copy(resourceId = ""))),
            next.copy(nodes = listOf(changed.copy(resourceId = ""))), field.copy(resourceId = ""), input).confirmed)
    }
}
