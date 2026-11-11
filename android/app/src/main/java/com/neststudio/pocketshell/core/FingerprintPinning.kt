package com.neststudio.pocketshell.core

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/**
 * Certificate pinning by SHA-256 of the server certificate DER (DESIGN.md §2: lowercase hex, no separators).
 * Replaces CA validation: the host's cert is self-signed, so only an exact fingerprint match is trusted.
 */
object FingerprintPinning {

    fun sha256Hex(der: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    /** Lowercases and strips ':' / whitespace so "AB:CD.." and "abcd.." compare equal. */
    fun normalize(fp: String): String =
        fp.filterNot { it == ':' || it.isWhitespace() }.lowercase()

    fun isValidFingerprint(normalized: String): Boolean =
        normalized.length == 64 && normalized.all { it in '0'..'9' || it in 'a'..'f' }

    /** Constant-time comparison of the certificate's fingerprint with the expected one. */
    fun matches(der: ByteArray, expectedFingerprint: String): Boolean {
        val expected = normalize(expectedFingerprint)
        if (!isValidFingerprint(expected)) return false
        return MessageDigest.isEqual(
            sha256Hex(der).toByteArray(Charsets.US_ASCII),
            expected.toByteArray(Charsets.US_ASCII),
        )
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/** Trusts exactly one certificate: the leaf whose DER SHA-256 equals [expectedFingerprint]. */
class FingerprintTrustManager(private val expectedFingerprint: String) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("클라이언트 인증서는 지원하지 않습니다")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("서버 인증서가 없습니다")
        if (!FingerprintPinning.matches(leaf.encoded, expectedFingerprint)) {
            throw CertificateException(
                "서버 인증서 지문 불일치: ${FingerprintPinning.sha256Hex(leaf.encoded)}"
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Accepts any host name (the cert is issued to CN=PocketShell, not to the LAN/Tailscale IP) but only when the
 * peer certificate is the pinned one. The TrustManager already enforces this during the handshake; this
 * re-check keeps hostname verification from being a blanket "accept all".
 */
class FingerprintHostnameVerifier(private val expectedFingerprint: String) : HostnameVerifier {
    override fun verify(hostname: String?, session: SSLSession?): Boolean {
        if (session == null) return false
        val leaf = try {
            session.peerCertificates.firstOrNull()
        } catch (e: SSLPeerUnverifiedException) {
            return false
        } ?: return false
        return FingerprintPinning.matches(leaf.encoded, expectedFingerprint)
    }
}
