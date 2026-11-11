package com.neststudio.pocketshell

import com.neststudio.pocketshell.core.FingerprintPinning
import com.neststudio.pocketshell.core.FingerprintTrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * a.der / b.der are self-signed RSA-2048 CN=PocketShell certificates generated with keytool.
 * Expected fingerprints were computed independently with `sha256sum a.der b.der`.
 */
class FingerprintPinningTest {

    private val fpA = "fb06f48341274fcce1699527855abb3a89fd35487bfb4f07debcfea4f41e5682"
    private val fpB = "dbebe1da5eb5658d907f3d1a437763f1c4d4763dbe1cd4887c4010d0ff27b657"

    private fun der(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("certs/$name")) { "missing $name" }.use { it.readBytes() }

    private fun cert(name: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(der(name).inputStream()) as X509Certificate

    @Test
    fun sha256OfDerMatchesSha256sum() {
        assertEquals(fpA, FingerprintPinning.sha256Hex(der("a.der")))
        assertEquals(fpB, FingerprintPinning.sha256Hex(der("b.der")))
    }

    @Test
    fun matchesIsExactAndFormatTolerant() {
        assertTrue(FingerprintPinning.matches(der("a.der"), fpA))
        assertTrue(FingerprintPinning.matches(der("a.der"), fpA.uppercase().chunked(2).joinToString(":")))
        assertFalse(FingerprintPinning.matches(der("a.der"), fpB))
        assertFalse(FingerprintPinning.matches(der("a.der"), fpA.dropLast(1)))
        assertFalse(FingerprintPinning.matches(der("a.der"), ""))
    }

    @Test
    fun trustManagerAcceptsOnlyPinnedCertificate() {
        val tm = FingerprintTrustManager(fpA)
        tm.checkServerTrusted(arrayOf(cert("a.der")), "RSA")
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(cert("b.der")), "RSA") }
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(), "RSA") }
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(null, "RSA") }
        assertEquals(0, tm.acceptedIssuers.size)
    }

    @Test
    fun trustManagerUsesLeafNotChainExtras() {
        // A pinned cert appearing later in the chain must not make an unpinned leaf trusted.
        val tm = FingerprintTrustManager(fpA)
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(cert("b.der"), cert("a.der")), "RSA")
        }
    }
}
