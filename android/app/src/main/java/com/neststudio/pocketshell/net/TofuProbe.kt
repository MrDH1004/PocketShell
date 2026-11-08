package com.neststudio.pocketshell.net

import com.neststudio.pocketshell.core.FingerprintPinning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Trust-on-first-use probe for PIN pairing (DESIGN.md §12.1): completes a TLS handshake with whatever
 * certificate the host presents and returns its DER SHA-256. Nothing is sent over this connection. The result
 * is only trusted after the user confirms its fpShort matches the PC screen; the claim itself then runs over a
 * client pinned to exactly this fingerprint ([PinnedHttp]).
 */
object TofuProbe {

    /** Accepts any server certificate; used solely to read the leaf for fingerprinting. */
    private class CapturingTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("클라이언트 인증서는 지원하지 않습니다")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw CertificateException("서버 인증서가 없습니다")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Returns the server certificate fingerprint (lowercase hex). Throws [IOException] on failure. */
    suspend fun fingerprint(host: String, port: Int, timeoutMs: Int = 4000): String = withContext(Dispatchers.IO) {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(CapturingTrustManager()), SecureRandom())
        Socket().use { raw ->
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            raw.soTimeout = timeoutMs
            (ctx.socketFactory.createSocket(raw, host, port, false) as SSLSocket).use { ssl ->
                ssl.startHandshake()
                val leaf = ssl.session.peerCertificates.firstOrNull()
                    ?: throw IOException("서버 인증서를 받지 못했습니다")
                FingerprintPinning.sha256Hex(leaf.encoded)
            }
        }
    }
}
