package ai.closepaw.chatgpt.control

/** Ephemeral owned IME geometry for local diagnosis and sequence verification. Never a ControlNode or API result. */
internal data class ImeGeometryNode(val index: Int, val parent: Int, val bounds: TapBounds,
    val kind: Kind, val eligible: Boolean) {
    enum class Kind { WRAPPER, CAPTION, OTHER }
}
