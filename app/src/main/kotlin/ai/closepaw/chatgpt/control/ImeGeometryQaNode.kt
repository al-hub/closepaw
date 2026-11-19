package ai.closepaw.chatgpt.control

/** Ephemeral, internal debug-fixture geometry. Never a ControlNode or API result. */
internal data class ImeGeometryQaNode(val index: Int, val parent: Int, val bounds: TapBounds,
    val kind: Kind, val eligible: Boolean) {
    enum class Kind { WRAPPER, CAPTION, OTHER }
}
