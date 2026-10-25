package ai.closepaw.chatgpt.control

/** Only a currently exposed semantic button in the OS-selected IME is a key target. */
internal object KeyboardKeyPolicy {
    enum class Kind { CHARACTER, SPACE, BACKSPACE, LAYOUT, SUBMIT }
    data class Key(val label: String, val kind: Kind)
    fun identify(text: String, description: String, className: String, clickable: Boolean, textEntryKey: Boolean = false): Key? {
        if (!clickable || !textEntryKey) return null
        val label = if (text == " " || (text.isEmpty() && description == " ")) "Space" else text.ifBlank { description }.trim()
        if (label.isEmpty() || label.length > 32 || InputPayloadPolicy.sensitive(label) || label.any { it.isISOControl() }) return null
        if (text.isNotBlank() && description.isNotBlank() && text.trim() != description.trim()) return null
        val kind = when (label.lowercase()) {
            "space", "spacebar", "공백", "스페이스" -> Kind.SPACE
            "backspace", "delete", "삭제", "지우기", "⌫", "⌦" -> Kind.BACKSPACE
            "shift", "caps lock", "⇧", "⇪", "🌐", "한/영", "한영", "?123", "123", "#+=", "abc", "한국어", "영어" -> Kind.LAYOUT
            "enter", "return", "↵", "⏎", "↩", "✓", "✔", "done", "search", "send", "go", "next", "엔터", "완료", "검색", "전송", "보내기", "이동", "다음" -> Kind.SUBMIT
            else -> {
                // isTextEntryKey also marks function keys in real IMEs. A single glyph is not
                // proof of literal text: unknown return/send/check/play/arrow icons fail closed.
                if (label.length != 1) return null
                val c = label.single()
                if (c.code !in 33..126 && c.code !in 0x1100..0x11ff && c.code !in 0x3130..0x318f && c.code !in 0xac00..0xd7a3) return null
                Kind.CHARACTER
            }
        }
        return Key(label, kind)
    }
    /** Predict only literal insertion/deletion in a known editable field. IME composition may differ. */
    fun expectedText(field: ControlNode, key: Key): String? {
        if (!field.editable || !field.focused || field.password || field.text.length > 2000) return null
        val start = field.selectionStart; val end = field.selectionEnd
        if (start > 0 && start < field.text.length && field.text[start - 1].isHighSurrogate() && field.text[start].isLowSurrogate()) return null
        if (end > 0 && end < field.text.length && field.text[end - 1].isHighSurrogate() && field.text[end].isLowSurrogate()) return null
        if (start !in 0..field.text.length || end !in start..field.text.length) return null
        val insert = when (key.kind) {
            Kind.CHARACTER -> key.label
            Kind.SPACE -> " "
            Kind.BACKSPACE -> ""
            else -> return null
        }
        val from = if (key.kind == Kind.BACKSPACE && start == end && start > 0)
            field.text.offsetByCodePoints(start, -1) else start
        val expected = field.text.substring(0, from) + insert + field.text.substring(end)
        return expected.takeIf { it.length <= 2000 && !InputPayloadPolicy.sensitive(it) }
    }
}

internal data class ControlKeyboard(val windowId: Int, val packageName: String, val inputMethodId: String,
    val readAllowed: Boolean = true, val interactAllowed: Boolean = true)
