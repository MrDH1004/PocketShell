package com.neststudio.pocketshell.core

import java.util.Base64

/**
 * The head of one request the in-app web proxy got from the WebView: where it goes and what to send there.
 * [connect] = an HTTPS (or WebSocket) tunnel: reply 200 and relay raw bytes. Otherwise a plain HTTP request whose
 * head is rewritten for the origin server ([forward]): origin-form path, no proxy headers, `Connection: close`
 * (one request per tunnel, so a kept-alive connection never carries a request meant for another site).
 */
data class ProxyHead(val connect: Boolean, val host: String, val port: Int, val forward: String, val authorization: String?) {
    companion object {
        /** Parses a request head (up to and including the blank line); null when it is not a proxy request. */
        fun parse(head: String): ProxyHead? {
            val lines = head.split("\r\n")
            val first = lines.firstOrNull()?.split(' ') ?: return null
            if (first.size != 3 || !first[2].startsWith("HTTP/")) return null
            val (method, target, version) = first
            var auth: String? = null
            val kept = ArrayList<String>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val name = line.substringBefore(':').trim().lowercase()
                when (name) {
                    "proxy-authorization" -> auth = line.substringAfter(':').trim()
                    "proxy-connection", "connection", "keep-alive" -> Unit
                    else -> kept.add(line)
                }
            }
            if (method.equals("CONNECT", ignoreCase = true)) {
                val (host, port) = hostPort(target, 443) ?: return null
                return ProxyHead(true, host, port, "", auth)
            }
            // Absolute form: http://host[:port]/path. https:// never arrives here (the WebView uses CONNECT).
            if (!target.startsWith("http://", ignoreCase = true)) return null
            val rest = target.substring(7)
            val slash = rest.indexOf('/').let { if (it < 0) rest.length else it }
            val (host, port) = hostPort(rest.substring(0, slash), 80) ?: return null
            val path = rest.substring(slash).ifEmpty { "/" }
            val forward = buildString {
                append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
                for (h in kept) append(h).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            return ProxyHead(false, host, port, forward, auth)
        }

        /** `host:port`, `[v6]:port` or a bare host with [defaultPort]. */
        fun hostPort(s: String, defaultPort: Int): Pair<String, Int>? {
            if (s.isEmpty()) return null
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end < 0) return null
                val host = s.substring(1, end)
                val port = s.substring(end + 1).removePrefix(":").ifEmpty { "$defaultPort" }.toIntOrNull() ?: return null
                return host to port
            }
            val colon = s.lastIndexOf(':')
            if (colon < 0) return s to defaultPort
            val port = s.substring(colon + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return s.substring(0, colon) to port
        }

        /** The `Proxy-Authorization` value the WebView sends for [user]:[secret] (Basic). */
        fun basic(user: String, secret: String): String =
            "Basic " + Base64.getEncoder().encodeToString("$user:$secret".toByteArray(Charsets.UTF_8))
    }
}
