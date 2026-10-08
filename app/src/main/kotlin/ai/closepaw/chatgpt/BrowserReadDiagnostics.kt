package ai.closepaw.chatgpt

import android.util.Log
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Privacy-safe bounded diagnostic metadata for ChatGPT browser read.
 * No URLs, user content or screenshots are stored.
 */
internal object BrowserReadDiagnostics {
    private const val CAPACITY = 32
    private val events = ArrayDeque<JsonObject>()

    @Synchronized
    fun record(app: String, reason: String, textChars: Int, screenshot: Boolean): String {
        val id = UUID.randomUUID().toString()
        val entry = buildJsonObject {
            put("request_id", id)
            put("timestamp_ms", System.currentTimeMillis())
            put("app", app.take(40))
            put("reason_code", reason)
            put("text_chars", textChars)
            put("screenshot_attached", screenshot)
        }
        if (events.size >= CAPACITY) events.removeFirst()
        events.addLast(entry)
        Log.i("ClosePawBrowserRead", "request_id=$id app=${app.take(40)} reason=$reason text_chars=$textChars screenshot=$screenshot")
        return id
    }

    @Synchronized
    fun snapshot(): JsonObject = buildJsonObject {
        put("status", "succeeded")
        put("summary", "Recent privacy-safe browser read diagnostics.")
        put("stored_events", events.size)
        put("events", buildJsonArray { events.forEach { add(it) } })
    }
}

/** Navigation controls alone are not meaningful browser page content. */
internal object BrowserReadQuality {
    private val navigationOnly = setOf(
        "최근 앱", "홈", "뒤로가기", "Recent apps", "Home", "Back",
        "Navigate up", "Close", "Tabs"
    )
    fun classify(content: String, hasScreenshot: Boolean): String {
        val lines = content.lines().map(String::trim).filter(String::isNotBlank)
        return when {
            lines.isNotEmpty() && lines.any { it !in navigationOnly } -> "ok"
            hasScreenshot -> "content_missing"
            else -> "empty"
        }
    }
}
