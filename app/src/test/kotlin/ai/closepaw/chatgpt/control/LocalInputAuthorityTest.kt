package ai.closepaw.chatgpt.control
import org.junit.Assert.*
import org.junit.Test
class LocalInputAuthorityTest {
    @Test fun localScopeNeverResumesRemoteAndRevocationCannotBeReactivated() {
        val remote = ControlSession("ended"); val epoch = remote.epoch
        var live = true; val scope = LocalInputAuthority { live }
        assertNull(scope.refusal()); assertTrue(scope.session.explicit)
        live = false; assertNotNull(scope.refusal()); live = true; assertNotNull(scope.refusal())
        assertEquals(ControlSession.State.ENDED, remote.state); assertEquals(epoch, remote.epoch)
        scope.close()
    }
}
