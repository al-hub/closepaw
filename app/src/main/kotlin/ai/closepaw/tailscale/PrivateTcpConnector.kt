package ai.closepaw.tailscale

import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal object PrivateTcpConnector {
    suspend fun connect(address: InetAddress, port: Int, timeoutMs: Int): TargetProbe = runInterruptible(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(address, port), timeoutMs) }
            TargetProbe("reachable")
        } catch (error: ConnectException) {
            val refused = error.message?.contains("ECONNREFUSED") == true || error.message?.contains("refused", ignoreCase = true) == true
            TargetProbe(if (refused) "target_port_refused" else "target_unreachable")
        } catch (_: SecurityException) { TargetProbe("probe_permission_denied")
        } catch (_: java.io.IOException) { TargetProbe("target_unreachable") }
    }
}
