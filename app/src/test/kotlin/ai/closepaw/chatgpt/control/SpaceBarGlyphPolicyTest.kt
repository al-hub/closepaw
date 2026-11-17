package ai.closepaw.chatgpt.control

import org.junit.Assert.*
import org.junit.Test

class SpaceBarGlyphPolicyTest {
    private val w = 120
    private val h = 40
    private fun image(bg: Int = 250, ink: Int = 15, shape: (Int, Int) -> Boolean) =
        IntArray(w * h) { if (shape(it % w, it / w)) ink else bg }
    private fun u(x: Int, y: Int) = x in 40..79 && y in 14..23 && (x <= 42 || x >= 77 || y >= 21)

    @Test fun conventionalSpaceGlyphWorksInBothThemes() {
        assertTrue(SpaceBarGlyphPolicy.matches(w, h, image(shape = ::u)))
        assertTrue(SpaceBarGlyphPolicy.matches(w, h, image(25, 245, ::u)))
    }

    @Test fun blankRuleFrameAndPartialOrSearchIconDoNotPass() {
        for (pixels in listOf(
            image { _, _ -> false },
            image { x, y -> x in 40..79 && y in 21..23 },
            image { x, y -> u(x, y) || (x in 40..79 && y == 14) },
            image { x, y -> u(x, y) && x < 77 },
            image { x, y -> u(x + 40, y) },
            image { x, y -> (x in 54..65 && y in 13..24 && (x in 54..55 || x in 64..65 || y in 13..14 || y in 23..24)) ||
                (x in 63..70 && y in 23..30 && kotlin.math.abs((x - 63) - (y - 23)) <= 1) },
        )) assertFalse(SpaceBarGlyphPolicy.matches(w, h, pixels))
    }
}
