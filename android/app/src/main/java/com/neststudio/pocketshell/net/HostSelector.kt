package com.neststudio.pocketshell.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.neststudio.pocketshell.core.HostOrder
import com.neststudio.pocketshell.core.NetSnapshot
import com.neststudio.pocketshell.core.Subnet
import java.net.Inet4Address
import java.nio.ByteBuffer

/**
 * Decides which PC address to try first (see [HostOrder]). The host that last worked is tried first again while
 * the phone's network is unchanged, so an unreachable preferred address doesn't cost a timeout on every request.
 * [network] == null keeps [hosts] in the given order (pairing).
 */
class HostSelector(private val hosts: List<String>, private val network: (() -> NetSnapshot)?) {
    @Volatile private var lastGood: Pair<NetSnapshot, String>? = null

    fun ordered(): List<String> {
        val net = network?.invoke() ?: return hosts
        val base = HostOrder.order(hosts, net)
        val good = lastGood
        return if (good != null && good.first == net && good.second in base) listOf(good.second) + (base - good.second) else base
    }

    fun reportSuccess(host: String) {
        val net = network?.invoke() ?: return
        lastGood = net to host
    }

    companion object {
        fun fixed(hosts: List<String>) = HostSelector(hosts, null)
    }
}

object NetworkState {
    /** Tailscale = a VPN network holding a 100.64.0.0/10 address; LAN = Wi-Fi/Ethernet IPv4 subnets. */
    @Suppress("DEPRECATION") // allNetworks: the only way to see the VPN and the underlying Wi-Fi together
    fun snapshot(context: Context): NetSnapshot {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetSnapshot.UNKNOWN
        var tailscale = false
        val lan = ArrayList<Subnet>()
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            val lp = cm.getLinkProperties(n) ?: continue
            val v4 = lp.linkAddresses.mapNotNull { la ->
                (la.address as? Inet4Address)?.let { Subnet(ByteBuffer.wrap(it.address).int, la.prefixLength) }
            }
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ->
                    if (v4.any { HostOrder.isTailscaleIp(it.address) }) tailscale = true
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                    lan.addAll(v4.filter { !HostOrder.isTailscaleIp(it.address) })
            }
        }
        return NetSnapshot(tailscale, lan.distinct())
    }
}
