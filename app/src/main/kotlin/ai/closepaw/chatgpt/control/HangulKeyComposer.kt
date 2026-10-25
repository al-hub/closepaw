package ai.closepaw.chatgpt.control

/** Conservative two-set Hangul prediction. This never proves that an IME is
 * composing: each predicted intermediate string still needs exact observation.
 * A committed syllable, autocorrection or a different IME stops the sequence. */
internal object HangulKeyComposer {
    private const val LEADS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private const val TAILS = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
    private val vowels = mapOf("ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ', "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ', "ㅡㅣ" to 'ㅢ')
    private val tails = mapOf("ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ', "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ', "ㄹㅅ" to 'ㄽ', "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ', "ㅂㅅ" to 'ㅄ')
    private fun syllable(l: Int, v: Int, t: Int = 0) = (0xac00 + (l * 21 + v) * 28 + t).toChar()
    fun append(text: String, key: String): String {
        if (text.isEmpty() || key.length != 1) return text + key
        val last = text.last(); val k = key.single(); val prefix = text.dropLast(1)
        val lead = LEADS.indexOf(last); val vowel = VOWELS.indexOf(k)
        if (lead >= 0 && vowel >= 0) return prefix + syllable(lead, vowel)
        if (last.code !in 0xac00..0xd7a3) return text + key
        val code = last.code - 0xac00; val l = code / 588; val v = (code % 588) / 28; val t = code % 28
        if (vowel >= 0) {
            if (t == 0) {
                val combined = vowels["${VOWELS[v]}$k"] ?: return text + key
                return prefix + syllable(l, VOWELS.indexOf(combined))
            }
            val split = tails.entries.singleOrNull { it.value == TAILS[t] }?.key
            val moved = split?.last() ?: TAILS[t]
            val nextLead = LEADS.indexOf(moved)
            if (nextLead < 0) return text + key
            val retained = split?.first()?.let { TAILS.indexOf(it) } ?: 0
            return prefix + syllable(l, v, retained) + syllable(nextLead, vowel)
        }
        val tail = TAILS.indexOf(k)
        if (tail <= 0) return text + key
        if (t == 0) return prefix + syllable(l, v, tail)
        val combined = tails["${TAILS[t]}$k"] ?: return text + key
        return prefix + syllable(l, v, TAILS.indexOf(combined))
    }
    /** Labels, never coordinates. Compounds are split into observed base keys. */
    fun labels(text: String): List<String> = buildList {
        for (c in text) {
            if (c.code !in 0xac00..0xd7a3) { add(if (c == ' ') "Space" else c.toString()); continue }
            val code = c.code - 0xac00
            add(LEADS[code / 588].toString())
            val vowel = VOWELS[(code % 588) / 28]
            val baseVowels = vowels.entries.singleOrNull { it.value == vowel }?.key ?: vowel.toString()
            baseVowels.forEach { add(it.toString()) }
            val tail = code % 28
            if (tail > 0) {
                val baseTails = tails.entries.singleOrNull { it.value == TAILS[tail] }?.key ?: TAILS[tail].toString()
                baseTails.forEach { add(it.toString()) }
            }
        }
    }
}
