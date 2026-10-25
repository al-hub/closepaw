package ai.closepaw.platform

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class AccessibilityPasteTargetTest {
    @Test fun focusedPasteOnlyFieldDoesNotRequireSetTextCapability() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val field = mockk<AccessibilityNodeInfo>(relaxed = true)
        val action = mockk<AccessibilityNodeInfo.AccessibilityAction>()
        every { action.id } returns AccessibilityNodeInfo.ACTION_PASTE
        every { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns field
        every { field.actionList } returns listOf(action)
        assertSame(field, AccessibilityNodeFinder.findFocusedEditableNode(root, AccessibilityNodeInfo.ACTION_PASTE))
    }
}
