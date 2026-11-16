package ai.closepaw.chatgpt.control

import kotlinx.serialization.json.*

/** An exact identity translation attested by the native window/compositor join.
 * Touchable bounds remain a restriction, never the buffer coordinate system. */
internal data class ImeSurfaceGeometry(
    val windowId: Int, val token: String, val surface: String, val frame: TapBounds,
    val touch: TapBounds, val width: Int, val height: Int, val serial: String,
    val challengeHash: String,
) {
    val fingerprint: String get() = ai.closepaw.chatgpt.operations.operationHash(
        "$windowId|$token|$surface|$frame|$touch|$width|$height|$serial")

    companion object {
        fun parse(raw: String, expectedId: Int, expectedTouch: TapBounds, expectedHash: String): ImeSurfaceGeometry? = runCatching {
            if (raw.length !in 1..2048) return null
            val o = Json.parseToJsonElement(raw).jsonObject
            if (o.keys != setOf("schema", "window_id", "token", "surface", "frame", "touch", "width", "height",
                    "translation", "rotation", "serial", "challenge_sha256")) return null
            fun number(key: String) = o.getValue(key).jsonPrimitive.takeUnless { it.isString }?.intOrNull ?: error("number")
            fun string(key: String) = o.getValue(key).jsonPrimitive.takeIf { it.isString }?.content ?: error("string")
            fun ints(key: String, count: Int): List<Int> = o.getValue(key).jsonArray.let { a ->
                require(a.size == count); a.map { it.jsonPrimitive.takeUnless { p -> p.isString }?.intOrNull ?: error("integer") }
            }
            fun bounds(key: String) = ints(key, 4).let { TapBounds(it[0], it[1], it[2], it[3]) }
            val frame = bounds("frame"); val touch = bounds("touch")
            val width = number("width"); val height = number("height"); val translation = ints("translation", 2)
            val token = string("token"); val surface = string("surface"); val serial = string("serial")
            if (number("schema") != 1 || number("window_id") != expectedId || number("rotation") != 0 ||
                string("challenge_sha256") != expectedHash || !Regex("[a-f0-9]{64}").matches(expectedHash) ||
                !Regex("""android\.os\.BinderProxy@[a-f0-9]+""").matches(token) ||
                !Regex("""VRI-InputMethod[$]_[0-9]+#[0-9]+""").matches(surface) ||
                !Regex("""[A-Za-z0-9_.:\[\]-]{1,160}""").matches(serial) ||
                width !in 1..8192 || height !in 1..8192 || width.toLong() * height > 8_000_000 ||
                !frame.valid || frame.left < 0 || frame.top < 0 || frame.right > 8192 || frame.bottom > 8192 ||
                frame.right - frame.left != width || frame.bottom - frame.top != height ||
                translation != listOf(frame.left, frame.top) || touch != expectedTouch || !frame.contains(touch)) return null
            ImeSurfaceGeometry(expectedId, token, surface, frame, touch, width, height, serial, expectedHash)
        }.getOrNull()
    }
}
