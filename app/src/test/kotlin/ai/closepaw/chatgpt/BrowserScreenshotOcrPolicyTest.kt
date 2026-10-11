package ai.closepaw.chatgpt

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserScreenshotOcrPolicyTest {
    @Test fun blogScreenshotKeepsArticleWhileDiscardingBrowserBars() {
        val lines = listOf(
            BrowserOcrLine("m.blog.naver.com", 75),
            BrowserOcrLine("blog", 120),
            BrowserOcrLine("(대전) 천주교 대전교구 주교좌 대흥동 성당", 265),
            BrowserOcrLine("대전광역시 중구 대종로 471", 610),
            BrowserOcrLine("대전 대흥동 성당은 1962년에 지어졌으며", 725),
            BrowserOcrLine("홈", 960),
        )
        assertEquals(
            "(대전) 천주교 대전교구 주교좌 대흥동 성당\n대전광역시 중구 대종로 471\n대전 대흥동 성당은 1962년에 지어졌으며",
            BrowserScreenshotOcrPolicy.pageText(lines, 1024),
        )
    }

    @Test fun toolbarsOnlyDoNotBecomeArticleText() {
        val lines = listOf(
            BrowserOcrLine("m.blog.naver.com", 60),
            BrowserOcrLine("사용 중, 읽기 모드", 140),
            BrowserOcrLine("새로고침", 160),
            BrowserOcrLine("홈", 940),
        )
        assertEquals("", BrowserScreenshotOcrPolicy.pageText(lines, 1024))
    }

    @Test fun singleShortHeadingDoesNotClaimFullArticleText() {
        val lines = listOf(BrowserOcrLine("블로그 게시글", 310))
        assertEquals("", BrowserScreenshotOcrPolicy.pageText(lines, 1024))
    }
}
