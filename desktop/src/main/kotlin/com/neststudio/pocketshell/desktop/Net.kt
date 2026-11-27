package com.neststudio.pocketshell.desktop

import com.neststudio.pocketshell.core.DiscoveredHost
import com.neststudio.pocketshell.core.HostOrder
import com.neststudio.pocketshell.core.NetSnapshot
import com.neststudio.pocketshell.core.PinPairing
import com.neststudio.pocketshell.core.Subnet
import com.neststudio.pocketshell.data.Pairing
import com.neststudio.pocketshell.net.ApiClient
import com.neststudio.pocketshell.net.HostSelector
import com.neststudio.pocketshell.net.PinnedHttp
import com.neststudio.pocketshell.net.Poster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import javax.swing.SwingUtilities
import javax.swing.Timer

/** [Poster] on the Swing event thread (Compose Desktop's UI thread too). */
object SwingPoster : Poster {
    private val timers = HashMap<Runnable, Timer>()

    override fun post(r: Runnable) = SwingUtilities.invokeLater(r)

    override fun postDelayed(r: Runnable, delayMillis: Long) {
        SwingUtilities.invokeLater {
            timers.remove(r)?.stop()
            val t = Timer(delayMillis.toInt().coerceAtLeast(1)) { timers.remove(r); r.run() }
            t.isRepeats = false
            timers[r] = t
            t.start()
        }
    }

    override fun removeCallbacks(r: Runnable) {
        if (SwingUtilities.isEventDispatchThread()) timers.remove(r)?.stop()
        else SwingUtilities.invokeLater { timers.remove(r)?.stop() }
    }
}

/** This computer's networks for [HostOrder]: Tailscale on (a 100.64.0.0/10 address), LAN IPv4 subnets. */
object DesktopNetwork {
    fun snapshot(): NetSnapshot {
        var tailscale = false
        val lan = ArrayList<Subnet>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return NetSnapshot.UNKNOWN
            while (ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    val ip = ByteBuffer.wrap(a.address).int
                    if (HostOrder.isTailscaleIp(ip)) tailscale = true
                    else if (ia.networkPrefixLength in 1..32) lan.add(Subnet(ip, ia.networkPrefixLength.toInt()))
                }
            }
        } catch (e: SocketException) {
            return NetSnapshot.UNKNOWN
        }
        return NetSnapshot(tailscale, lan.distinct())
    }
}

/** One client per PC (shares its OkHttp client with the terminal WebSockets). */
object Apis {
    private val cache = HashMap<String, Pair<Pairing, ApiClient>>()

    @Synchronized
    fun of(p: Pairing): ApiClient {
        cache[p.id]?.let { if (it.first == p) return it.second }
        val api = ApiClient(HostSelector(p.hosts) { DesktopNetwork.snapshot() }, p.port, p.token, PinnedHttp.client(p.fingerprint))
        cache[p.id] = p to api
        return api
    }
}

/**
 * LAN discovery (DESIGN.md §12.2): `PKSHELL?1` to every IPv4 broadcast address and 255.255.255.255 on UDP 7381,
 * replies collected for 1.5 s (the Android LanDiscovery without the Wi-Fi multicast lock).
 */
object Discovery {
    suspend fun discover(windowMs: Long = PinPairing.DISCOVERY_WINDOW_MS): List<DiscoveredHost> = withContext(Dispatchers.IO) {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val req = PinPairing.DISCOVERY_REQUEST.toByteArray(Charsets.US_ASCII)
            var sent = 0
            for (addr in broadcastAddresses()) {
                try {
                    socket.send(DatagramPacket(req, req.size, addr, PinPairing.DISCOVERY_PORT))
                    sent++
                } catch (e: IOException) {
                    Log.warn("discovery send to $addr failed: ${e.message}")
                }
            }
            if (sent == 0) throw IOException("검색 요청을 보낼 네트워크가 없음 (Wi-Fi·랜 연결 확인)")
            val replies = ArrayList<DiscoveredHost>()
            val buf = ByteArray(8192)
            val deadline = System.currentTimeMillis() + windowMs
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                socket.soTimeout = remaining.toInt().coerceAtLeast(1)
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (e: SocketTimeoutException) { break }
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                DiscoveredHost.parse(text, packet.address?.hostAddress)?.let { replies.add(it) }
            }
            DiscoveredHost.merge(replies)
        }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) if (ia.address is Inet4Address) ia.broadcast?.let { out.add(it) }
            }
        } catch (e: SocketException) {
            Log.warn("interface enumeration failed: ${e.message}")
        }
        out.add(InetAddress.getByName("255.255.255.255"))
        return out.toList()
    }
}
