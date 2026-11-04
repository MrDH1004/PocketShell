package com.mpta.client.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.mpta.client.core.DiscoveredHost
import com.mpta.client.core.PinPairing
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

/**
 * LAN discovery (DESIGN.md §12.2): sends `MPTA?1` to every IPv4 broadcast address of the up interfaces and
 * to 255.255.255.255 on UDP 7381, then collects replies for 1.5 s. Many devices drop incoming broadcast/
 * multicast traffic while the screen is on Wi-Fi power save unless a [WifiManager.MulticastLock] is held.
 */
object LanDiscovery {
    private const val TAG = "LanDiscovery"

    suspend fun discover(context: Context, windowMs: Long = PinPairing.DISCOVERY_WINDOW_MS): List<DiscoveredHost> =
        withContext(Dispatchers.IO) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
            val lock = wifi?.createMulticastLock("mpta-discovery")?.apply { setReferenceCounted(false) }
            try {
                lock?.acquire()
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val req = PinPairing.DISCOVERY_REQUEST.toByteArray(Charsets.US_ASCII)
                    var sent = 0
                    for (addr in broadcastAddresses()) {
                        try {
                            socket.send(DatagramPacket(req, req.size, addr, PinPairing.DISCOVERY_PORT))
                            sent++
                        } catch (e: IOException) {
                            Log.w(TAG, "send to $addr failed: ${e.message}")
                        }
                    }
                    if (sent == 0) throw IOException("검색 요청을 보낼 수 있는 네트워크가 없습니다 (Wi-Fi 연결 확인)")

                    val replies = ArrayList<DiscoveredHost>()
                    val buf = ByteArray(8192)
                    val deadline = System.currentTimeMillis() + windowMs
                    while (true) {
                        val remaining = deadline - System.currentTimeMillis()
                        if (remaining <= 0) break
                        socket.soTimeout = remaining.toInt().coerceAtLeast(1)
                        val packet = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(packet)
                        } catch (e: SocketTimeoutException) {
                            break
                        }
                        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                        DiscoveredHost.parse(text, packet.address?.hostAddress)?.let { replies.add(it) }
                    }
                    DiscoveredHost.merge(replies)
                }
            } finally {
                if (lock?.isHeld == true) lock.release()
            }
        }

    /** Broadcast address of each up, non-loopback IPv4 interface plus the limited broadcast address. */
    private fun broadcastAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    if (ia.address is Inet4Address) ia.broadcast?.let { out.add(it) }
                }
            }
        } catch (e: SocketException) {
            Log.w(TAG, "interface enumeration failed: ${e.message}")
        }
        out.add(InetAddress.getByName("255.255.255.255"))
        return out.toList()
    }
}
