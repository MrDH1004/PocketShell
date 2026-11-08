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
 * a.der / b.der are self-signed RSA-2048 CN=pkshell certificates generated with keytool.
 * Expected fingerprints were computed independently with `sha256sum a.der b.der`.
 */
class FingerprintPinningTest {

    private val fpA = "6ba4c49772f9fc3b16edf5b076b1528e746ab629ee9ed44b7f21731a4654d869"
    private val fpB = "936b2288a5a46a2e15eef0b558c072674d130795a5a567049d53ebae310d1d31"

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
