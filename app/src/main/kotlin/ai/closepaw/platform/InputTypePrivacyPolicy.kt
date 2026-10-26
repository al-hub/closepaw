package ai.closepaw.platform

/** Official EditorInfo input-type bits; NULL-class terminal flags are not text. */
internal object InputTypePrivacyPolicy {
    fun password(inputType: Int): Boolean {
        val cls=inputType and 0x0f
        val variation=inputType and 0x0ff0
        return (cls==1 && variation in setOf(0x80,0x90,0xe0)) || (cls==2 && variation==0x10)
    }
}
