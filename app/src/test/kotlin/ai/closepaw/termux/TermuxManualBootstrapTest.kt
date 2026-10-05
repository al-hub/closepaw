package ai.closepaw.termux

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TermuxManualBootstrapTest {
    @Test fun `bootstrap command never contains a permanent token literal`() {
        val command = TermuxManualBootstrap.command()
        assertThat(command).doesNotContain("test-token")
        assertThat(command).contains("read -r CLOSEPAW_TOKEN")
    }

    @Test fun `bootstrap persists token in private file and starts bridge from it`() {
        val command = TermuxManualBootstrap.command()
        assertThat(command).contains("umask 077")
        assertThat(command).contains("~/.closepaw/token")
        assertThat(command).contains("chmod 600")
        assertThat(command).contains("cat ~/.closepaw/token")
        assertThat(command).contains("nohup python3 ~/.closepaw/bridge.py")
    }

    @Test fun `bootstrap installs idempotent Termux Boot startup script`() {
        val command = TermuxManualBootstrap.command()
        assertThat(command).contains("~/.termux/boot")
        assertThat(command).contains("10-closepaw-bridge")
        assertThat(command).contains("~/.closepaw/token")
        assertThat(command).contains("chmod 700")
    }

    @Test fun `boot script reads token at runtime instead of embedding secret`() {
        val script = TermuxManualBootstrap.bootScript()
        assertThat(script).contains("cat \"\$HOME/.closepaw/token\"")
        assertThat(script).doesNotContain("test-token")
        assertThat(script).contains("127.0.0.1:18422")
    }
}

