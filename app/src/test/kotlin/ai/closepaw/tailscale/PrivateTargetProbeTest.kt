package ai.closepaw.tailscale

import java.net.InetAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PrivateTargetProbeTest {
    @Test fun publicAndMixedDnsAnswersNeverOpenASocket() = runTest {
        var connects = 0
        val probe = PrivateTargetProbe(
            { listOf(InetAddress.getByName("100.100.0.1"), InetAddress.getByName("8.8.8.8")) },
            { _, _, _ -> connects++; TargetProbe("reachable") },
        )
        assertEquals("target_outside_private_network", probe.probe(PrivateTarget("pc.ts.net", 22), 2000).reason)
        assertEquals(0, connects)
    }

    @Test fun tcpRefusalIsDistinguishedFromMissingRoute() = runTest {
        val probe = PrivateTargetProbe({ listOf(InetAddress.getByName("100.100.0.1")) }, { _, _, _ -> TargetProbe("target_port_refused") })
        assertEquals("target_port_refused", probe.probe(PrivateTarget("pc.ts.net", 22), 2000).reason)
    }

    @Test fun dnsTimeoutIsBoundedAndHasNoConnectSideEffect() = runTest {
        var connects = 0
        val probe = PrivateTargetProbe({ delay(10000); emptyList() }, { _, _, _ -> connects++; TargetProbe("reachable") })
        assertEquals("target_probe_timeout", probe.probe(PrivateTarget("pc.ts.net", 22), 2000).reason)
        assertEquals(0, connects)
    }

    @Test fun resolutionFailureIsNotReportedAsVpnAuthenticationFailure() = runTest {
        val probe = PrivateTargetProbe({ emptyList() }, { _, _, _ -> error("must not connect") })
        assertEquals("target_dns_failed", probe.probe(PrivateTarget("pc.ts.net", 22), 2000).reason)
    }

    @Test fun actualPrivateTcpEndpointAndClosedPortAreDistinguished() = kotlinx.coroutines.runBlocking {
        val address = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it.address.size == 4 && PrivateTargetPolicy.isPrivate(it) }
        org.junit.Assume.assumeNotNull(address)
        val host = address!!
        val server = java.net.ServerSocket(0, 1, host)
        server.soTimeout = 1000
        val port = server.localPort
        try {
            val probe = PrivateTargetProbe({ listOf(host) }, PrivateTcpConnector::connect)
            assertEquals("reachable", probe.probe(PrivateTarget(host.hostAddress!!, port), 2000).reason)
            server.accept().close()
            server.close()
            assertEquals("target_port_refused", probe.probe(PrivateTarget(host.hostAddress!!, port), 2000).reason)
        } finally { server.close() }
    }
}
