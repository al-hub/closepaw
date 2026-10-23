package ai.closepaw.ui.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IslandMoveVerifierTest {
    @Test fun `acknowledgement alone never verifies relocation`() {
        assertThat(IslandMoveVerifier.verify(null, null, true)).isEqualTo(IslandMoveVerdict.UNKNOWN)
        assertThat(IslandMoveVerifier.verify(IslandPosition(10, 20), null, true)).isEqualTo(IslandMoveVerdict.UNKNOWN)
    }

    @Test fun `unchanged position does not count as movement`() {
        assertThat(IslandMoveVerifier.verify(IslandPosition(10, 20), IslandPosition(10, 20), true))
            .isEqualTo(IslandMoveVerdict.NOT_MOVED)
    }

    @Test fun `actual movement requires fresh measurements and acknowledged gesture`() {
        val before = IslandPosition(10, 20)
        val after = IslandPosition(120, 220)
        assertThat(IslandMoveVerifier.verify(before, after, true)).isEqualTo(IslandMoveVerdict.MOVED)
        assertThat(IslandMoveVerifier.verify(before, after, false)).isEqualTo(IslandMoveVerdict.UNKNOWN)
    }
}
