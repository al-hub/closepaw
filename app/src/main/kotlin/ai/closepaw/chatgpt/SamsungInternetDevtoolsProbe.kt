package ai.closepaw.chatgpt

import ai.closepaw.browser.setup.ShizukuShellRunner
import java.io.File

internal data class DevtoolsSocketProbeResult(
    val source: String,
    val readable: Boolean,
    val sockets: List<String>,
)

internal class SamsungInternetDevtoolsProbe(
    private val procNetUnix: File = File("/proc/net/unix"),
    private val shellRunner: ShizukuShellRunner = ShizukuShellRunner(),
) {
    suspend fun probe(): DevtoolsSocketProbeResult {
        val appRead = runCatching { procNetUnix.readText() }.getOrNull()
        if (appRead != null) {
            return DevtoolsSocketProbeResult(
                source = "app_uid",
                readable = true,
                sockets = parseCandidateSockets(appRead),
            )
        }

        val shell = runCatching {
            shellRunner.run(
                arrayOf(
                    "sh",
                    "-c",
                    "grep -Ei 'devtools|sbrowser|samsung.*remote' /proc/net/unix || true",
                )
            )
        }.getOrNull()

        if (shell == null || shell.exitCode == -1) {
            return DevtoolsSocketProbeResult(
                source = "unavailable",
                readable = false,
                sockets = emptyList(),
            )
        }

        return DevtoolsSocketProbeResult(
            source = "shizuku_shell",
            readable = true,
            sockets = parseCandidateSockets(shell.stdout),
        )
    }

    companion object {
        internal fun parseCandidateSockets(procNetUnix: String): List<String> {
            val token = Regex("""@[^\s]+""")
            return procNetUnix
                .lineSequence()
                .filter { line ->
                    val lower = line.lowercase()
                    lower.contains("devtools") ||
                        lower.contains("sbrowser") ||
                        (lower.contains("samsung") && lower.contains("remote"))
                }
                .mapNotNull { line -> token.find(line)?.value }
                .distinct()
                .sorted()
                .toList()
        }
    }
}
