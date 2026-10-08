package ai.closepaw.chatgpt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserReadDiagnosticsTest {
    @Test fun navigationOnlyScreenshotIsNotTextReadSuccess() {
        assertEquals("content_missing", BrowserReadQuality.classify("최근 앱\n홈\n뒤로가기", true))
    }

    @Test fun realArticleTextIsSuccessful() {
        assertEquals("ok", BrowserReadQuality.classify("뉴스 본문입니다", true))
    }

    @Test fun noTextOrScreenshotIsEmpty() {
        assertEquals("empty", BrowserReadQuality.classify("", false))
    }

    @Test fun diagnosticsStoresMetadataWithoutPageContent() {
        val id = BrowserReadDiagnostics.record("samsung_internet", "content_missing", 19, true)
        val snapshot = BrowserReadDiagnostics.snapshot().toString()
        assertTrue(snapshot.contains(id))
        assertTrue(snapshot.contains("content_missing"))
        assertFalse(snapshot.contains("뉴스 본문입니다"))
    }

    @Test fun diagnosticsRingBufferIsBounded() {
        repeat(40) { BrowserReadDiagnostics.record("chrome", "ok", 5, false) }
        val snapshot = BrowserReadDiagnostics.snapshot()
        assertEquals("32", snapshot["stored_events"].toString())
    }
}
