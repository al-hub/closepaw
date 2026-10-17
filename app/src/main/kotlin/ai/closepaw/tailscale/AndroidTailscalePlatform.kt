package ai.closepaw.tailscale

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.InetAddresses
import android.net.NetworkCapabilities
import android.os.CancellationSignal
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Android IPC and network observation. Never reads another app's private files or credentials. */
internal class AndroidTailscalePlatform(
    context: Context,
    private val interfaceAddresses: () -> List<InetAddress> = {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp }.flatMap { it.inetAddresses.toList() }
    },
) : TailscalePlatform {
    private val context = context.applicationContext
    private val receiver = ComponentName(PACKAGE, RECEIVER)
    private val targetProbe = PrivateTargetProbe(::resolve, PrivateTcpConnector::connect)

    override suspend fun observe(): TailscaleLocalState = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val info = try { pm.getApplicationInfo(PACKAGE, 0) } catch (_: PackageManager.NameNotFoundException) {
            return@withContext TailscaleLocalState(installed = false, connectSupported = false)
        } catch (_: SecurityException) {
            return@withContext TailscaleLocalState(observationError = "package_observation_denied")
        }
        val component = try { pm.getReceiverInfo(receiver, 0) } catch (_: PackageManager.NameNotFoundException) { null
        } catch (_: SecurityException) { return@withContext TailscaleLocalState(observationError = "receiver_observation_denied") }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return@withContext TailscaleLocalState(observationError = "network_observation_unavailable")
        val vpn = runCatching { connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } }
        val overlay = runCatching { connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                connectivity.getLinkProperties(network)?.linkAddresses?.any { PrivateTargetPolicy.isTailnetAddress(it.address) } == true
        } || interfaceAddresses().any(PrivateTargetPolicy::isTailnetAddress) }
        TailscaleLocalState(
            installed = true, appEnabled = info.enabled,
            connectSupported = component?.let { it.enabled && it.exported } == true,
            controlPermissionGranted = component?.permission?.let { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } ?: true,
            vpnPresent = vpn.getOrDefault(false), overlayAddressPresent = overlay.getOrDefault(false),
            versionName = runCatching { pm.getPackageInfo(PACKAGE, 0).versionName?.take(80) }.getOrNull(),
            observationError = if (vpn.isFailure) "network_observation_unavailable" else null,
        )
    }

    override suspend fun probe(target: PrivateTarget, timeoutMs: Int) = withContext(Dispatchers.IO) { targetProbe.probe(target, timeoutMs) }

    override suspend fun connect(): ConnectDispatch = withContext(Dispatchers.IO) {
        try {
            context.sendBroadcast(Intent(CONNECT_ACTION).setComponent(receiver))
            ConnectDispatch(true)
        } catch (_: SecurityException) { ConnectDispatch(false, "connect_request_denied")
        } catch (_: Exception) { ConnectDispatch(false, "connect_request_failed") }
    }

    private suspend fun resolve(host: String): List<InetAddress> {
        if (InetAddresses.isNumericAddress(host)) return listOf(InetAddresses.parseNumericAddress(host))
        return suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            DnsResolver.getInstance().query(null, host, DnsResolver.FLAG_EMPTY, context.mainExecutor, signal,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        if (continuation.isActive) continuation.resume(if (rcode == 0) answer else emptyList())
                    }
                    override fun onError(error: DnsResolver.DnsException) {
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                })
        }
    }

    companion object {
        const val PACKAGE = "com.tailscale.ipn"
        const val RECEIVER = "com.tailscale.ipn.IPNReceiver"
        const val CONNECT_ACTION = "com.tailscale.ipn.CONNECT_VPN"
    }
}
