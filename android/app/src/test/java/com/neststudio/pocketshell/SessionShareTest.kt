package com.neststudio.pocketshell

import com.neststudio.pocketshell.core.ClaimResult
import com.neststudio.pocketshell.core.ShareGrant
import com.neststudio.pocketshell.core.ShareInvite
import com.neststudio.pocketshell.data.Pairing
import com.neststudio.pocketshell.data.PairingCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionShareTest {
    private val fp = "ab".repeat(32)

    @Test
    fun claimCarriesTheShare() {
        val c = ClaimResult.parse("""{"deviceId":"d1","token":"t1","fp":"$fp","share":{"sessionId":"ext-42","readOnly":true,"expiresAt":"2026-11-17T14:00:00.000Z","title":"flowdesk"}}""")
        assertEquals(ShareGrant("ext-42", true, "2026-11-17T14:00:00.000Z", "flowdesk"), c.share)
        assertNull(ClaimResult.parse("""{"deviceId":"d1","token":"t1","fp":"$fp"}""").share)
        assertNull(ShareGrant.fromJson(org.json.JSONObject("""{"sessionId":""}""")))
    }

    @Test
    fun expiry() {
        val g = ShareGrant("s", false, "2026-11-17T14:00:00.000Z", "")
        val at = java.time.Instant.parse("2026-11-17T14:00:00Z").toEpochMilli()
        assertFalse(g.isExpired(at - 1))
        assertTrue(g.isExpired(at))
        assertFalse(ShareGrant("s", false, "", "").isExpired(Long.MAX_VALUE)) // unknown end: not judged here
    }

    @Test
    fun sharedSessionIsItsOwnEntryAndSurvivesTheCodec() {
        val own = Pairing("PC", listOf("192.168.0.10"), 7380, fp, "d0", "t0")
        val shared = Pairing("PC", listOf("192.168.0.10"), 7380, fp, "d1", "t1", share = ShareGrant("abc", true, "2026-11-17T14:00:00.000Z", "proj"))
        assertNotEquals(own.id, shared.id)
        val list = PairingCodec.upsert(PairingCodec.upsert(emptyList(), own), shared)
        assertEquals(2, list.size) // the share does not replace the owner's pairing with the same PC
        val back = PairingCodec.decode(PairingCodec.encode(list))
        assertEquals(list, back)
        assertNull(back[0].share)
    }

    @Test
    fun inviteParsesAndFormats() {
        val i = ShareInvite.parse("""{"pin":"123456","expiresAt":"x","fpShort":"AAAA-BBBB-CCCC-DDDD","hosts":["192.168.0.10","fd7a::1"],"port":7380,"readOnly":false,"hours":4,"title":"flowdesk"}""")
        assertEquals("123 456", i.pinSpaced)
        assertEquals("192.168.0.10, [fd7a::1]", i.addresses)
        assertEquals("192.168.0.10:47999", i.copy(hosts = listOf("192.168.0.10"), port = 47999).addresses)
        assertEquals(4, i.hours)
        assertEquals("flowdesk", i.title)
    }
}
