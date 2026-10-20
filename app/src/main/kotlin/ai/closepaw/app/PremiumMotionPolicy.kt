package ai.closepaw.app

/** Pure rendering policy: user control and reduced-motion settings always win. */
internal object PremiumMotionPolicy {
    const val FRAME_MS = 33L
    const val TARGET_MS = 900L
    const val ACTION_MS = 720L
    fun animate(interactive: Boolean, attached: Boolean, systemAnimations: Boolean, exploring: Boolean,
        userControlled: Boolean): Boolean = interactive && attached && systemAnimations && !exploring && !userControlled
    fun progress(now: Long, started: Long, duration: Long): Float =
        if (duration <= 0L) 1f else ((now - started).toFloat() / duration).coerceIn(0f, 1f)
    fun fade(progress: Float): Float = (1f - progress.coerceIn(0f, 1f)).let { it * it }
}
