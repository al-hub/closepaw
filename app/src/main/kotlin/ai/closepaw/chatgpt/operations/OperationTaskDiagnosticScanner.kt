package ai.closepaw.chatgpt.operations

import java.io.Reader
import kotlinx.serialization.json.*

/** Strict, bounded JSON scan. Payload strings/numbers are validated, never retained or interpreted. */
internal class OperationTaskDiagnosticScanner(
    private val input: Reader,
    private val historical: Boolean = false,
    private val onRecord: ((JsonObject) -> Unit)? = null,
) {
    internal data class Counts(val tasks: Int, val active: Int, val terminal: Int, val unknown: Int)
    private var next = input.read()
    private var nodes = 0
    private var retainedChars = 0
    private val ids = HashSet<String>()
    private val requests = HashSet<String>()
    private var recordText: StringBuilder? = null
    private fun advance() {
        recordText?.let {
            bound(it.length < MAX_RECORD_CHARS)
            it.append(next.toChar())
        }
        next = input.read()
    }
    private fun whitespace() { while (next == 32 || next == 9 || next == 10 || next == 13) advance() }
    private fun expect(ch: Char) { require(next == ch.code); advance() }
    private fun bound(ok: Boolean) { if (!ok) throw OperationTaskDiagnosticLimitException() }
    private fun node(depth: Int) { bound(depth <= MAX_DEPTH && ++nodes <= MAX_NODES) }
    private fun string(capture: Boolean): String {
        expect('"')
        val result = if (capture) StringBuilder() else null
        while (next != '"'.code) {
            require(next >= 32)
            val ch = if (next == '\\'.code) {
                advance()
                when (next) {
                    '"'.code, '\\'.code, '/'.code -> next.toChar().also { advance() }
                    'b'.code -> '\b'.also { advance() }
                    'f'.code -> '\u000c'.also { advance() }
                    'n'.code -> '\n'.also { advance() }
                    'r'.code -> '\r'.also { advance() }
                    't'.code -> '\t'.also { advance() }
                    'u'.code -> {
                        var value = 0
                        repeat(4) {
                            advance()
                            val digit = when (next) {
                                in 48..57 -> next - 48
                                in 65..70 -> next - 65 + 10
                                in 97..102 -> next - 97 + 10
                                else -> throw IllegalArgumentException("Invalid hex escape")
                            }
                            value = value * 16 + digit
                        }
                        advance(); value.toChar()
                    }
                    else -> throw IllegalArgumentException("Invalid escape")
                }
            } else next.toChar().also { advance() }
            if (result != null) { bound(result.length < MAX_TOKEN_CHARS); result.append(ch) }
        }
        advance()
        return result?.toString().orEmpty()
    }
    private fun literal(text: String) { text.forEach { expect(it) } }
    private fun digits() { require(next in 48..57); while (next in 48..57) advance() }
    private fun number() {
        if (next == '-'.code) advance()
        if (next == '0'.code) advance() else { require(next in 49..57); digits() }
        if (next == '.'.code) { advance(); digits() }
        if (next == 'e'.code || next == 'E'.code) {
            advance(); if (next == '+'.code || next == '-'.code) advance(); digits()
        }
    }
    private fun value(depth: Int) {
        node(depth); whitespace()
        when (next) {
            '"'.code -> string(false)
            '{'.code -> {
                advance(); whitespace()
                val keys = HashSet<String>()
                if (next != '}'.code) while (true) {
                    node(depth); require(keys.add(string(true)))
                    whitespace(); expect(':'); whitespace(); value(depth + 1); whitespace()
                    if (next != ','.code) break
                    advance(); whitespace()
                }
                expect('}')
            }
            '['.code -> {
                advance(); whitespace()
                if (next != ']'.code) while (true) {
                    value(depth + 1); whitespace()
                    if (next != ','.code) break
                    advance(); whitespace()
                }
                expect(']')
            }
            't'.code -> literal("true")
            'f'.code -> literal("false")
            'n'.code -> literal("null")
            else -> number()
        }
    }
    private fun task(): String? {
        if (historical) { nodes = 0; retainedChars = 0 }
        if (onRecord != null) recordText = StringBuilder()
        node(2); expect('{'); whitespace()
        var id: String? = null
        var request: String? = null
        var state: String? = null
        val keys = HashSet<String>()
        var keyChars = 0
        if (next != '}'.code) while (true) {
            node(2)
            val key = string(true)
            keyChars += key.length
            bound(keys.size < 1024 && keyChars <= 65536)
            require(keys.add(key))
            whitespace(); expect(':'); whitespace()
            if (key == "task_id" || key == "request_id" || key == "state") {
                node(3)
                if (next == '"'.code) {
                    val text = string(true)
                    retainedChars += text.length
                    bound(retainedChars <= MAX_RETAINED_CHARS)
                    when (key) { "task_id" -> id = text; "request_id" -> request = text; else -> state = text }
                } else {
                    // Optional missing/null request/state remain unknown; non-string identities are invalid.
                    require(key != "task_id" && next == 'n'.code)
                    literal("null")
                }
            } else value(3)
            whitespace()
            if (next != ','.code) break
            advance(); whitespace()
        }
        expect('}')
        require(!id.isNullOrBlank() && ids.add(id!!))
        if (request != null) require(requests.add(request!!))
        val raw = recordText
        recordText = null
        if (raw != null) onRecord?.invoke(Json.parseToJsonElement(raw.toString()).jsonObject)
        return state
    }
    fun scan(): Counts {
        node(1); whitespace(); expect('['); whitespace()
        var tasks = 0; var active = 0; var terminal = 0; var unknown = 0
        if (next != ']'.code) while (true) {
            if (!historical && tasks >= OperationTaskStore.MAX_TASKS) throw OperationTaskDiagnosticCountException()
            when (task()) {
                "queued", "running", "cancel_requested" -> active++
                "succeeded", "failed", "cancelled", "timed_out", "interrupted" -> terminal++
                else -> unknown++
            }
            tasks++; whitespace()
            if (next != ','.code) break
            advance(); whitespace()
        }
        expect(']'); whitespace(); require(next == -1)
        return Counts(tasks, active, terminal, unknown)
    }
    companion object {
        const val MAX_DEPTH = 32
        const val MAX_NODES = 262144
        const val MAX_TOKEN_CHARS = 4096
        const val MAX_RETAINED_CHARS = 262144
        // At most one complete record is materialized; the legacy file itself
        // remains byte-bounded independently. Covers escaped 128k outputs.
        const val MAX_RECORD_CHARS = 4 * 1024 * 1024
    }
}
internal class OperationTaskDiagnosticCountException : IllegalArgumentException("Diagnostic record bound")
internal class OperationTaskDiagnosticByteException : IllegalArgumentException("Diagnostic byte bound")
