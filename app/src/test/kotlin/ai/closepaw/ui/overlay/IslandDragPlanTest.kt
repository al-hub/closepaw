package ai.closepaw.ui.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IslandDragPlanTest {
    @Test fun `requires an explicit hold before dragging`() {
        assertThat(IslandDragPlan.create(30, 60, 220, 300, 449, 500)).isNull()
        assertThat(IslandDragPlan.create(30, 60, 220, 300, 450, 500)).isNotNull()
    }

    @Test fun `rejects ineffective or out of bounds gestures`() {
        assertThat(IslandDragPlan.create(30, 60, 30, 60, 450, 500)).isNull()
        assertThat(IslandDragPlan.create(-1, 60, 220, 300, 450, 500)).isNull()
        assertThat(IslandDragPlan.create(30, 60, 220, 300, 450, 500, 200, 200)).isNull()
    }

    @Test fun `rejects invalid gesture duration`() {
        assertThat(IslandDragPlan.create(30, 60, 220, 300, 450, 0)).isNull()
        assertThat(IslandDragPlan.create(30, 60, 220, 300, 450, 5001)).isNull()
    }

    @Test fun `returns a valid immutable plan`() {
        assertThat(IslandDragPlan.create(30, 60, 220, 300, 450, 500))
            .isEqualTo(IslandDragPlan(30, 60, 220, 300, 450, 500))
    }
}
