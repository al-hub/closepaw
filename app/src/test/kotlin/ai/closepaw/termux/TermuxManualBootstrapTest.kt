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


    @Test fun `manual setup command deploys bridge without embedding auth token`() {
        val command = TermuxManualBootstrap.manualSetupCommand("YnJpZGdl")
        assertThat(command).contains("YnJpZGdl")
        assertThat(command).contains("base64 -d > ~/.closepaw/bridge.py")
        assertThat(command).contains("python3 -m py_compile ~/.closepaw/bridge.py")
        assertThat(command).contains("command -v python3")
        assertThat(command).contains("read -rs CLOSEPAW_TOKEN")
        assertThat(command).doesNotContain("test-token")
    }


    @Test fun `all Local Bridge launch paths disable idle self shutdown`() {
        val boot = TermuxManualBootstrap.bootScript()
        val command = TermuxManualBootstrap.command()
        val manual = TermuxManualBootstrap.manualSetupCommand("YnJpZGdl")

        listOf(boot, command, manual).forEach {
            assertThat(it).contains(TermuxBridgeLaunchPolicy.PERSISTENT_DAEMON_ARGS)
        }
    }


    @Test fun `boot script keeps TermuxService task alive by execing bridge in foreground`() {
        val script = TermuxManualBootstrap.bootScript()
        assertThat(script).contains("exec env CLOSEPAW_BRIDGE_TOKEN=")
        assertThat(script).contains("python3 \"\$BRIDGE\"")
        assertThat(script).doesNotContain("nohup python3")
        assertThat(script).doesNotContain("</dev/null &")
    }

    @Test fun `boot script records attempt and outcome diagnostics`() {
        val script = TermuxManualBootstrap.bootScript()
        assertThat(script).contains("boot.log")
        assertThat(script).contains("attempt %s")
        assertThat(script).contains("already_ready %s")
        assertThat(script).contains("exec_start %s")
    }

    @Test fun `boot script reads token at runtime instead of embedding secret`() {
        val script = TermuxManualBootstrap.bootScript()
        assertThat(script).contains("TOKEN_FILE=\"\$HOME/.closepaw/token\"")
        assertThat(script).contains("cat \"\$TOKEN_FILE\"")
        assertThat(script).doesNotContain("test-token")
        assertThat(script).contains("127.0.0.1:18422")
    }
}

