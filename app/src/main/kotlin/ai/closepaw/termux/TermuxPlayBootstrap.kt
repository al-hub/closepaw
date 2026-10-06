package ai.closepaw.termux

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64
import kotlin.concurrent.thread

internal object TermuxPlayBootstrap {
    private const val TIMEOUT_MS = 300_000
    private val lock = Any()
    private var active: ServerSocket? = null

    data class Prepared(val command: String, val port: Int)

    fun prepare(context: Context): Prepared {
        val installer = installerScript(context.applicationContext)
        val nonce = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = TIMEOUT_MS
        }
        synchronized(lock) {
            active?.close()
            active = server
        }
        val path = "/bootstrap/$nonce"
        thread(name = "closepaw-termux-bootstrap", isDaemon = true) {
            try {
                server.use { socket ->
                    socket.accept().use { client ->
                        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
                        val requestLine = reader.readLine().orEmpty()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val ok = requestLine == "GET $path HTTP/1.1"
                        val body = if (ok) installer else "not found\n"
                        val status = if (ok) "200 OK" else "404 Not Found"
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        client.getOutputStream().apply {
                            write(
                                "HTTP/1.1 $status\r\nContent-Type: text/x-shellscript; charset=utf-8\r\n" +
                                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                        .toByteArray(Charsets.US_ASCII)
                            )
                            write(bytes)
                            flush()
                        }
                    }
                }
            } catch (_: Exception) {
                // Timeout, replacement, or disconnect invalidates this one-shot endpoint.
            } finally {
                synchronized(lock) {
                    if (active === server) active = null
                }
            }
        }
        return Prepared(
            command = "curl -fsS http://127.0.0.1:${server.localPort}$path | sh",
            port = server.localPort,
        )
    }

    internal fun installerScript(context: Context): String =
        installerScript(
            bridge64 = TermuxManualBootstrap.bridgePayload(context),
            token = TermuxManualBootstrap.token(context),
            bootScript = TermuxManualBootstrap.bootScript(),
        )

    internal fun installerScript(bridge64: String, token: String, bootScript: String): String {
        val token64 = Base64.getEncoder().encodeToString(token.toByteArray(Charsets.UTF_8))
        val boot64 = Base64.getEncoder().encodeToString(bootScript.toByteArray(Charsets.UTF_8))
        return """#!/data/data/com.termux/files/usr/bin/sh
set -eu
umask 077
mkdir -p "${'$'}HOME/.closepaw" "${'$'}HOME/closepaw/workspace" "${'$'}HOME/closepaw/artifacts" "${'$'}HOME/closepaw/logs" "${'$'}HOME/.termux/boot"
if ! command -v python3 >/dev/null 2>&1; then
  pkg install -y python
fi
printf '%s' '$bridge64' | base64 -d > "${'$'}HOME/.closepaw/bridge.py"
python3 -m py_compile "${'$'}HOME/.closepaw/bridge.py"
printf '%s' '$token64' | base64 -d > "${'$'}HOME/.closepaw/token"
chmod 600 "${'$'}HOME/.closepaw/token"
printf '%s' '$boot64' | base64 -d > "${'$'}HOME/.termux/boot/10-closepaw-bridge"
chmod 700 "${'$'}HOME/.termux/boot/10-closepaw-bridge"
PROP="${'$'}HOME/.termux/termux.properties"
touch "${'$'}PROP"
if grep -q '^[[:space:]]*allow-external-apps=' "${'$'}PROP"; then
  sed -i 's/^[[:space:]]*allow-external-apps=.*/allow-external-apps=true/' "${'$'}PROP"
else
  printf '\nallow-external-apps=true\n' >> "${'$'}PROP"
fi
CLOSEPAW_BRIDGE_TOKEN="${'$'}(cat "${'$'}HOME/.closepaw/token")" nohup python3 "${'$'}HOME/.closepaw/bridge.py" >/dev/null 2>"${'$'}HOME/closepaw/logs/bridge.err" </dev/null &
sleep 1
curl -fsS --max-time 2 http://127.0.0.1:18422/v1/health
printf '\nCLOSEPAW_BOOTSTRAP=ok\n'
""".trimIndent()
    }
}
