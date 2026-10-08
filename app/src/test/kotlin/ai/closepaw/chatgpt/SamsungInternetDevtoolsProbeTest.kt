package ai.closepaw.chatgpt

import org.junit.Assert.assertEquals
import org.junit.Test

class SamsungInternetDevtoolsProbeTest {

    @Test
    fun parsesOnlyRelevantAbstractSockets() {
        val proc = """
            Num       RefCount Protocol Flags    Type St Inode Path
            00000000: 00000002 00000000 00010000 0001 01 1 @chrome_devtools_remote
            00000000: 00000002 00000000 00010000 0001 01 2 @com.sec.android.app.sbrowser_devtools_remote
            00000000: 00000002 00000000 00010000 0001 01 3 @unrelated_socket
            00000000: 00000002 00000000 00010000 0001 01 4 @samsung_remote_debug
        """.trimIndent()

        assertEquals(
            listOf(
                "@chrome_devtools_remote",
                "@com.sec.android.app.sbrowser_devtools_remote",
                "@samsung_remote_debug",
            ),
            SamsungInternetDevtoolsProbe.parseCandidateSockets(proc),
        )
    }

    @Test
    fun returnsEmptyWhenNoRelevantSocketExists() {
        assertEquals(
            emptyList<String>(),
            SamsungInternetDevtoolsProbe.parseCandidateSockets(
                "00000000: 00000002 00000000 00010000 0001 01 3 @unrelated_socket"
            ),
        )
    }
}
