package ai.closepaw.tailscale

import java.net.InetAddress

internal object PrivateTargetPolicy {
    fun validHost(host: String): Boolean {
        if (host.length !in 1..253) return false
        if (':' in host) return host.matches(Regex("[0-9a-fA-F:]+")) &&
            runCatching { InetAddress.getByName(host).address.size == 16 }.getOrDefault(false)
        if (host.all { it.isDigit() || it == '.' }) {
            val parts = host.split('.')
            return parts.size == 4 && parts.all { part -> part.toIntOrNull() in 0..255 && (part == "0" || !part.startsWith('0')) }
        }
        return host.split('.').all { it.length in 1..63 && it.matches(Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?")) }
    }

    fun isPrivate(address: InetAddress): Boolean {
        val bytes = address.address.map { it.toInt() and 255 }
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress) return false
        return if (bytes.size == 4) {
            bytes[0] == 10 || bytes[0] == 172 && bytes[1] in 16..31 ||
                bytes[0] == 192 && bytes[1] == 168 || bytes[0] == 100 && bytes[1] in 64..127
        } else bytes.size == 16 && (bytes[0] and 254) == 252
    }

    fun isTailnetAddress(address: InetAddress): Boolean {
        val b = address.address.map { it.toInt() and 255 }
        return b.size == 4 && b[0] == 100 && b[1] in 64..127 ||
            b.size == 16 && b.take(6) == listOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0)
    }
}
