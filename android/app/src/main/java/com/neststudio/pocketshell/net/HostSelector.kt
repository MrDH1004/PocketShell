package com.neststudio.pocketshell.net

import com.neststudio.pocketshell.core.HostOrder
import com.neststudio.pocketshell.core.NetSnapshot

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
