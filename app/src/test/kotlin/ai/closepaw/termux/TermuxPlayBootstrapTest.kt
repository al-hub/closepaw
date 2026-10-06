package ai.closepaw.termux

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import org.junit.Test

class TermuxPlayBootstrapTest {
    @Test
    fun `installer deploys bridge token boot launcher and starts bridge`() {
        val script = TermuxPlayBootstrap.installerScript(
            bridge64 = Base64.getEncoder().encodeToString("print('bridge')".toByteArray()),
            token = "secret-token",
            bootScript = "#!/bin/sh\necho boot",
        )

        assertThat(script).contains(".closepaw/bridge.py")
        assertThat(script).contains("python3 -m py_compile")
        assertThat(script).contains(".closepaw/token")
        assertThat(script).contains("chmod 600")
        assertThat(script).contains(".termux/boot/10-closepaw-bridge")
        assertThat(script).contains("chmod 700")
        assertThat(script).contains("nohup python3")
        assertThat(script).contains("127.0.0.1:18422/v1/health")
        assertThat(script).contains("CLOSEPAW_BOOTSTRAP=ok")
    }

    @Test
    fun `installer never emits auth token as plaintext`() {
        val script = TermuxPlayBootstrap.installerScript(
            bridge64 = "YnJpZGdl",
            token = "super-secret-token",
            bootScript = "#!/bin/sh",
        )

        assertThat(script).doesNotContain("super-secret-token")
        assertThat(script).contains(
            Base64.getEncoder().encodeToString("super-secret-token".toByteArray())
        )
    }

    @Test
    fun `installer idempotently enables external apps`() {
        val script = TermuxPlayBootstrap.installerScript(
            bridge64 = "YnJpZGdl",
            token = "token",
            bootScript = "#!/bin/sh",
        )

        assertThat(script).contains("allow-external-apps=true")
        assertThat(script).contains("sed -i")
    }
}
