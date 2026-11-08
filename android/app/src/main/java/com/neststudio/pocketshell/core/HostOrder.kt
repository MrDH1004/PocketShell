package com.neststudio.pocketshell.core

/** An IPv4 subnet of one of the phone's own interfaces (address as a big-endian int). */
data class Subnet(val address: Int, val prefix: Int) {
    fun contains(ip: Int): Boolean {
        if (prefix !in 1..32) return false
        val mask = if (prefix == 32) -1 else (-1 shl (32 - prefix))
        return (ip and mask) == (address and mask)
    }
}

/** What the phone's network looks like right now. */
data class NetSnapshot(val tailscaleOn: Boolean, val lanSubnets: List<Subnet>) {
    companion object {
        val UNKNOWN = NetSnapshot(false, emptyList())
    }
}

/**
 * Orders the PC's advertised addresses for the current network:
 * - Tailscale on: Tailscale (100.64.0.0/10) first, then addresses on the phone's own LAN subnet, then the rest.
 * - Tailscale off: same-LAN first, then the rest, Tailscale last.
 * Order within each group is the pairing order.
 */
object HostOrder {
    fun order(hosts: List<String>, net: NetSnapshot): List<String> {
        val tailscale = hosts.filter { isTailscale(it) }
        val sameLan = hosts.filter { h -> !isTailscale(h) && ipv4(h)?.let { ip -> net.lanSubnets.any { it.contains(ip) } } == true }
        val rest = hosts.filter { it !in tailscale && it !in sameLan }
        return if (net.tailscaleOn) tailscale + sameLan + rest else sameLan + rest + tailscale
    }

    fun isTailscale(host: String): Boolean = ipv4(host)?.let { isTailscaleIp(it) } == true

    /** 100.64.0.0/10 (Tailscale's CGNAT range). */
    fun isTailscaleIp(ip: Int): Boolean = (ip ushr 24) == 100 && ((ip ushr 16) and 0xFF) in 64..127

    /** Dotted IPv4 → big-endian int, or null for anything else (names, IPv6). */
    fun ipv4(host: String): Int? {
        val parts = host.trim().split('.')
        if (parts.size != 4) return null
        var v = 0
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val n = p.toInt()
            if (n > 255) return null
            v = (v shl 8) or n
        }
        return v
    }
}
