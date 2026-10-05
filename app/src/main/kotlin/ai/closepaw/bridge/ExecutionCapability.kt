package ai.closepaw.bridge

/** A capability describes what the caller needs, not which app provides it. */
enum class ExecutionCapability {
    LINUX_SHELL,
    ANDROID_SHELL,
    ANDROID_INTENT,
    ACCESSIBILITY,
    SSH
}
