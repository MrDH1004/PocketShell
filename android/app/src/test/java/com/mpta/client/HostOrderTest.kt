package com.mpta.client

import com.mpta.client.core.HostOrder
import com.mpta.client.core.NetSnapshot
import com.mpta.client.core.Subnet
import com.mpta.client.net.HostSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostOrderTest {
    private val lan = "192.168.18.49"
    private val ts = "100.80.34.88"
    private val other = "10.0.0.5"
    private val hosts = listOf(lan, other, ts)
    private val homeWifi = Subnet(HostOrder.ipv4("192.168.18.77")!!, 24)

    @Test
    fun tailscaleOnPutsTailscaleFirstThenSameLan() {
        assertEquals(listOf(ts, lan, other), HostOrder.order(hosts, NetSnapshot(true, listOf(homeWifi))))
    }

    @Test
    fun tailscaleOffPutsSameLanFirstAndTailscaleLast() {
        val otherLan = Subnet(HostOrder.ipv4("10.0.0.20")!!, 24)
        assertEquals(listOf(other, lan, ts), HostOrder.order(hosts, NetSnapshot(false, listOf(otherLan))))
        assertEquals(listOf(lan, other, ts), HostOrder.order(hosts, NetSnapshot(false, listOf(homeWifi))))
        assertEquals(listOf(lan, other, ts), HostOrder.order(hosts, NetSnapshot.UNKNOWN))
    }

    @Test
    fun tailscaleRangeAndParsing() {
        assertTrue(HostOrder.isTailscale("100.64.0.1"))
        assertTrue(HostOrder.isTailscale("100.127.255.255"))
        assertFalse(HostOrder.isTailscale("100.128.0.1"))
        assertFalse(HostOrder.isTailscale("100.63.0.1"))
        assertFalse(HostOrder.isTailscale("pc.local"))
        assertNull(HostOrder.ipv4("256.1.1.1"))
        assertNull(HostOrder.ipv4("fe80::1"))
        assertFalse(Subnet(HostOrder.ipv4("192.168.18.77")!!, 24).contains(HostOrder.ipv4("192.168.19.49")!!))
    }

    @Test
    fun selectorRemembersWorkingHostUntilNetworkChanges() {
        var net = NetSnapshot(true, listOf(homeWifi))
        val sel = HostSelector(hosts) { net }
        assertEquals(ts, sel.ordered().first())
        sel.reportSuccess(lan) // Tailscale timed out, LAN answered
        assertEquals(listOf(lan, ts, other), sel.ordered())
        net = NetSnapshot(true, emptyList()) // left home Wi-Fi
        assertEquals(listOf(ts, lan, other), sel.ordered())
    }

    @Test
    fun fixedSelectorKeepsGivenOrder() {
        assertEquals(hosts, HostSelector.fixed(hosts).ordered())
    }
}
