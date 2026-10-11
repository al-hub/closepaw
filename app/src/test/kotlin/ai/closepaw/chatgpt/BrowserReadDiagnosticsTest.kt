package ai.closepaw.chatgpt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserReadDiagnosticsTest {
    @Test fun navigationOnlyScreenshotIsNotTextReadSuccess() {
        assertEquals("content_missing", BrowserReadQuality.classify("최근 앱\n홈\n뒤로가기", true))
    }

    @Test fun samsungInternetBrowserControlsAreNotMistakenForPageText() {
        val browserControls = """
            북마크에 추가
            안전한 연결
            ‎m.search.naver.com
            새로고침
            뒤로
            앞으로
            홈
            브라우징 어시스트
            북마크
            탭
            98
            도구
        """.trimIndent()

        assertEquals("content_missing", BrowserReadQuality.classify(browserControls, true))
        assertEquals("empty", BrowserReadQuality.classify(browserControls, false))
    }

    @Test fun browserControlsAndArticleHeadlineAreMeaningfulText() {
        assertEquals(
            "ok",
            BrowserReadQuality.classify("m.naver.com\n홈\n창원한마음병원 척추 진료 시작", true),
        )
    }

    @Test fun browserChromeOnlyNeverBecomesUserFacingPageText() {
        val chromeOnly = "북마크에 추가\\n홈\\n‎m.naver.com\\n탭"
        assertEquals("", BrowserReadQuality.pageContent(chromeOnly, "content_missing"))
        assertEquals(chromeOnly, BrowserReadQuality.pageContent(chromeOnly, "ok"))
    }

    @Test fun samsungReadingModeToolbarIsNotPageContent() {
        val browserUi = listOf(
            "북마크에 추가", "안전한 연결", "‎m.blog.naver.com",
            "사용 중, 읽기 모드", "새로고침", "뒤로", "앞으로",
            "홈", "브라우징 어시스트", "북마크", "탭", "98", "도구"
        ).joinToString("\n")
        assertEquals("content_missing", BrowserReadQuality.classify(browserUi, true))
        assertEquals("", BrowserReadQuality.pageContent(browserUi, "content_missing"))
        assertEquals("empty", BrowserReadQuality.classify(browserUi, false))
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
