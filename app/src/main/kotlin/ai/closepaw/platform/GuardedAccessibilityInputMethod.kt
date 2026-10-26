package ai.closepaw.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi

/** Android's accessibility input API; this does not replace the user's keyboard.
 * Only binding generations are retained. No input text or clipboard is stored. */
@RequiresApi(33)
internal class GuardedAccessibilityInputMethod(service: AccessibilityService, private val onSensitiveEditor: () -> Unit = {}) : InputMethod(service) {
    internal data class ImagePrivacy(val packageName: String,val sensitive: Boolean)
    @Volatile internal var imagePrivacy: ImagePrivacy? = null
        private set
    @Volatile var generation = 0L
        private set
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        imagePrivacy=ImagePrivacy(attribute.packageName.orEmpty(),InputTypePrivacyPolicy.password(attribute.inputType))
        if(imagePrivacy?.sensitive==true) onSensitiveEditor()
        generation++
    }
    // Do not call the default composition-clearing implementation on another IME's input.
    override fun onFinishInput() { imagePrivacy=null; generation++ }
}
