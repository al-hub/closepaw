package ai.closepaw.chatgpt.control

import kotlin.math.abs

/** Recognizes an open-top space-bar pictogram in a certified, isolated key crop.
 * Layout is independently checked. A line, empty key, ring or generic icon
 * does not provide role evidence. No coordinates, themes or IME IDs are used. */
internal object SpaceBarGlyphPolicy {
    fun matches(width: Int, height: Int, luminance: IntArray): Boolean {
        if (width !in 18..960 || height !in 10..960 || luminance.size != width * height) return false
        val bg = listOf(luminance[0], luminance[width - 1],
            luminance[(height - 1) * width], luminance.last()).sorted()
        if (bg.last() - bg.first() > 22) return false
        val background = (bg[1] + bg[2]) / 2
        val center = BooleanArray(luminance.size)
        var left = width
        var right = -1
        var top = height
        var bottom = -1
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            if (abs(luminance[i] - background) < 64) continue
            center[i] = true
            left = minOf(left, x); right = maxOf(right, x)
            top = minOf(top, y); bottom = maxOf(bottom, y)
        }
        val w = right - left + 1
        val h = bottom - top + 1
        if (w < 9 || h < 4 || w * 2 < h * 5 || w > h * 14 ||
            left < 2 || right >= width - 2 || top < 2 || bottom >= height - 2 ||
            w * 5 > width * 4 || h * 3 > height * 2 ||
            abs(left - (width - right - 1)) > width / 5) return false
        val edge = maxOf(2, w / 5)
        val mid = (left + edge)..(right - edge)
        val upper = top until top + maxOf(1, h / 2)
        val base = (top + h / 2)..bottom
        fun filled(x: Int, y: Int) = center[y * width + x]
        if (upper.any { y -> mid.any { x -> filled(x, y) } }) return false
        if (upper.count { y -> (left until left + edge).any { x -> filled(x, y) } &&
                (right - edge + 1..right).any { x -> filled(x, y) } } * 4 < upper.count() * 3) return false
        val baseline = base.lastOrNull { y -> (left..right).count { x -> filled(x, y) } * 10 >= w * 9 }
            ?: return false
        return baseline >= bottom - 1 && (top..baseline).all { y ->
            (left..right).any { x -> filled(x, y) }
        }
    }
}
