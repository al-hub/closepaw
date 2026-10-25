package ai.closepaw.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi

/** Android's accessibility input API; this does not replace the user's keyboard.
 * Only binding generations are retained. No input text or clipboard is stored. */
@RequiresApi(33)
internal class GuardedAccessibilityInputMethod(service: AccessibilityService) : InputMethod(service) {
    @Volatile var generation = 0L
        private set
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) { generation++ }
    // Do not call the default composition-clearing implementation on another IME's input.
    override fun onFinishInput() { generation++ }
}
