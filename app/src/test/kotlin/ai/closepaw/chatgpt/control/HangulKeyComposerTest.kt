package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class HangulKeyComposerTest {
    @Test fun predictsExactIntermediateComposition() {
        var text = ""
        for ((key, expected) in listOf("ㅎ" to "ㅎ", "ㅏ" to "하", "ㄴ" to "한", "ㄱ" to "한ㄱ", "ㅡ" to "한그", "ㄹ" to "한글")) {
            text = HangulKeyComposer.append(text, key); assertEquals(expected, text)
        }
    }
    @Test fun allHangulSyllablesRoundTripThroughLiteralLabels() {
        for (c in '가'..'힣') {
            val actual = HangulKeyComposer.labels(c.toString()).fold("") { value, key -> HangulKeyComposer.append(value, key) }
            assertEquals(c.toString(), actual)
        }
    }
    @Test fun movesFinalConsonantAndSplitsCompoundForNextVowel() {
        assertEquals("가나", HangulKeyComposer.append("간", "ㅏ"))
        assertEquals("달가", HangulKeyComposer.append("닭", "ㅏ"))
        assertEquals("과", HangulKeyComposer.append("고", "ㅏ"))
        assertEquals("A1! ", listOf("A", "1", "!", " ").fold("") { v, k -> HangulKeyComposer.append(v, k) })
    }
}
