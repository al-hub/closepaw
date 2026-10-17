package ai.closepaw.tailscale

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TailscaleRecoveryTest {
    private val target = PrivateTarget("100.100.0.1", 22)

    @Test fun alreadyReachableDoesNotChangeVpnEvenIfTailscaleMissing() = runTest {
        val platform = FakePlatform().apply { local = TailscaleLocalState(installed = false); probeResult = TargetProbe("reachable") }
        val result = TailscaleRecovery(platform, { true }).ensure(target, 5000)
        assertTrue(result.success)
        assertEquals("target_already_reachable", result.state)
        assertEquals(0, platform.connects)
    }

    @Test fun missingDisabledUnsupportedAndPermissionErrorsAreDistinct() = runTest {
        val cases = listOf(
            TailscaleLocalState(installed = false) to "app_not_installed",
            TailscaleLocalState(appEnabled = false) to "app_disabled",
            TailscaleLocalState(connectSupported = false) to "connect_not_supported",
            TailscaleLocalState(controlPermissionGranted = false) to "connect_permission_missing",
        )
        for ((state, expected) in cases) {
            val platform = FakePlatform().apply { local = state }
            val result = TailscaleRecovery(platform, { true }).ensure(target, 5000)
            assertFalse(result.success)
            assertEquals(expected, result.state)
            assertEquals(0, platform.connects)
        }
    }

    @Test fun connectionRequestMustBeFollowedBySuccessfulTargetProbe() = runTest {
        val platform = FakePlatform().apply {
            onConnect = { probeResult = TargetProbe("reachable"); local = local.copy(vpnPresent = true, overlayAddressPresent = true) }
        }
        val result = TailscaleRecovery(platform, { true }).ensure(target, 5000)
        assertTrue(result.success)
        assertTrue(result.connectRequested)
        assertEquals("target_reachable", result.state)
        assertEquals(1, platform.connects)
    }

    @Test fun broadcastAcceptanceAloneNeverMeansConnectedOrReplaysShell() = runTest {
        val platform = FakePlatform()
        val recovery = TailscaleRecovery(platform, { true }, nowMs = { testScheduler.currentTime })
        val result = recovery.ensure(target, 3000)
        assertFalse(result.success)
        assertEquals("connection_unconfirmed", result.state)
        assertTrue(result.connectRequested)
        assertEquals(1, platform.connects)
        assertEquals("target_unreachable", result.probe?.reason)
        assertEquals("connection_cooldown", recovery.ensure(target, 3000).state)
        assertEquals(1, platform.connects)
    }

    @Test fun targetRefusalAndNonPrivateDestinationDoNotTriggerVpnRepair() = runTest {
        for (reason in listOf("target_port_refused", "target_outside_private_network")) {
            val platform = FakePlatform().apply { probeResult = TargetProbe(reason) }
            assertEquals(reason, TailscaleRecovery(platform, { true }).ensure(target, 5000).state)
            assertEquals(0, platform.connects)
        }
    }

    @Test fun unattributedVpnIsNotReplaced() = runTest {
        val platform = FakePlatform().apply { local = local.copy(vpnPresent = true) }
        assertEquals("vpn_conflict_or_unidentified", TailscaleRecovery(platform, { true }).ensure(target, 5000).state)
        assertEquals(0, platform.connects)
    }

    @Test fun overlayAddressAloneCannotProveVpnOwnershipOrJustifyReplacingIt() = runTest {
        val platform = FakePlatform().apply { local = local.copy(vpnPresent = true, overlayAddressPresent = true) }
        assertEquals("vpn_active_target_unreachable", TailscaleRecovery(platform, { true }).ensure(target, 5000).state)
        assertEquals(0, platform.connects)
    }

    @Test fun localPermissionRevocationStopsRecoveryBeforeBroadcast() = runTest {
        val platform = FakePlatform()
        assertEquals("control_disabled", TailscaleRecovery(platform, { false }).ensure(target, 5000).state)
        assertEquals(0, platform.connects)
    }

    @Test fun permissionRevokedAfterDispatchStopsObservationWithoutDisconnectingVpn() = runTest {
        var enabled = true
        val platform = FakePlatform().apply { onConnect = { enabled = false } }
        val result = TailscaleRecovery(platform, { enabled }).ensure(target, 5000)
        assertEquals("control_disabled", result.state)
        assertTrue(result.connectRequested)
        assertFalse(result.success)
        assertEquals(1, platform.connects)
    }

    @Test fun concurrentRequestsDoNotDispatchDuplicateConnects() = runTest {
        val gate = CompletableDeferred<Unit>()
        val platform = FakePlatform().apply { onConnect = { gate.await() } }
        val recovery = TailscaleRecovery(platform, { true }, nowMs = { testScheduler.currentTime })
        val first = async { recovery.ensure(target, 5000) }
        runCurrent()
        assertEquals("recovery_in_progress", recovery.ensure(target, 5000).state)
        gate.complete(Unit)
        first.await()
        assertEquals(1, platform.connects)
    }

    @Test fun requestFailureIsReportedWithoutInventingLoginOrPermissionState() = runTest {
        val platform = FakePlatform().apply { dispatch = ConnectDispatch(false, "connect_request_denied") }
        val result = TailscaleRecovery(platform, { true }).ensure(target, 5000)
        assertEquals("connect_request_denied", result.state)
        assertFalse(result.success)
    }

    @Test fun readOnlyStatusNeverConnects() = runTest {
        val platform = FakePlatform()
        val result = TailscaleRecovery(platform, { true }).status(target)
        assertEquals("target_unreachable", result.state)
        assertEquals(0, platform.connects)
    }

    @Test fun initialDiagnosisTimeoutDoesNotClaimThatConnectionWasRequested() = runTest {
        val platform = FakePlatform().apply { probeDelayMs = 10000 }
        val result = TailscaleRecovery(platform, { true }).ensure(target, 2000)
        assertEquals("diagnosis_timeout", result.state)
        assertFalse(result.connectRequested)
        assertEquals(0, platform.connects)
    }

    @Test fun privateAddressesIncludeTailnetAndSubnetRoutesButNotPublicOrLoopback() {
        listOf("100.64.0.1", "100.127.255.254", "192.168.1.2", "10.0.0.2", "172.16.0.1", "fd7a:115c:a1e0::1").forEach {
            assertTrue(it, PrivateTargetPolicy.isPrivate(java.net.InetAddress.getByName(it)))
        }
        listOf("100.63.255.255", "100.128.0.1", "8.8.8.8", "127.0.0.1", "0.0.0.0", "::1", "169.254.1.1").forEach {
            assertFalse(it, PrivateTargetPolicy.isPrivate(java.net.InetAddress.getByName(it)))
        }
        listOf("https://host", "host;id", "user@host", "host/abc", "", "fe80::1%wlan0", ":::invalid", "::::", "999.1.1.1", "010.0.0.1", "123").forEach {
            assertFalse(it, PrivateTargetPolicy.validHost(it))
        }
    }

    private class FakePlatform : TailscalePlatform {
        var local = TailscaleLocalState()
        var probeResult = TargetProbe("target_unreachable")
        var dispatch = ConnectDispatch(true)
        var connects = 0
        var probeDelayMs = 0L
        var onConnect: suspend FakePlatform.() -> Unit = {}
        override suspend fun observe() = local
        override suspend fun probe(target: PrivateTarget, timeoutMs: Int): TargetProbe { delay(probeDelayMs); return probeResult }
        override suspend fun connect(): ConnectDispatch { connects++; onConnect(); return dispatch }
    }
}
