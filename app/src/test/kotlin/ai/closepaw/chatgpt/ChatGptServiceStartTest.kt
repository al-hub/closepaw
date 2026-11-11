package ai.closepaw.chatgpt

import org.junit.Assert.*
import org.junit.Test

class ChatGptServiceStartTest {
    @Test fun successfulSubmissionRequestsServiceExactlyOnce() {
        var starts = 0
        assertTrue(ChatGptMcpService.requestConnectionStart("connect_start_blocked") { starts++ })
        assertEquals(1, starts)
    }

    @Test fun permissionRevokedAtStartReportsReasonWithoutCrashOrRetry() {
        var starts = 0
        assertFalse(ChatGptMcpService.requestConnectionStart("connect_start_blocked") {
            starts++
            throw SecurityException("private test detail must not enter status")
        })
        assertEquals(1, starts)
        assertEquals("connect_start_blocked:SecurityException", ChatGptMcpService.connectionState.value.error)
        assertFalse(ChatGptMcpService.connectionState.value.running)
    }

    @Test fun backgroundStartRefusalUsesSameBoundedReportingForRestore() {
        var starts = 0
        assertFalse(ChatGptMcpService.requestConnectionStart("auto_connect_blocked") {
            starts++
            throw IllegalStateException("background start refused")
        })
        assertEquals(1, starts)
        assertEquals("auto_connect_blocked:IllegalStateException", ChatGptMcpService.connectionState.value.error)
        assertFalse(ChatGptMcpService.connectionState.value.running)
    }
}
