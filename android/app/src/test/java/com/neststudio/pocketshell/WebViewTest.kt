package com.neststudio.pocketshell

import com.neststudio.pocketshell.core.ProxyHead
import com.neststudio.pocketshell.core.WebBookmark
import com.neststudio.pocketshell.core.WebBookmarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewTest {
    @Test
    fun connectGoesThroughAsATunnel() {
        val h = ProxyHead.parse("CONNECT intra.corp:443 HTTP/1.1\r\nHost: intra.corp:443\r\nProxy-Authorization: Basic abc\r\n\r\n")!!
        assertEquals(true, h.connect)
        assertEquals("intra.corp" to 443, h.host to h.port)
        assertEquals("Basic abc", h.authorization)
    }

    @Test
    fun plainHttpIsRewrittenForTheOrigin() {
        val h = ProxyHead.parse(
            "GET http://10.0.0.5:8080/wiki/a?b=1 HTTP/1.1\r\nHost: 10.0.0.5:8080\r\nProxy-Connection: keep-alive\r\n" +
                "Proxy-Authorization: Basic abc\r\nCookie: s=1\r\n\r\n",
        )!!
        assertEquals(false, h.connect)
        assertEquals("10.0.0.5" to 8080, h.host to h.port)
        assertEquals("GET /wiki/a?b=1 HTTP/1.1\r\nHost: 10.0.0.5:8080\r\nCookie: s=1\r\nConnection: close\r\n\r\n", h.forward)
    }

    @Test
    fun hostPortForms() {
        assertEquals("intranet" to 80, ProxyHead.hostPort("intranet", 80))
        assertEquals("::1" to 8080, ProxyHead.hostPort("[::1]:8080", 80))
        assertNull(ProxyHead.hostPort("a:99999", 80))
        assertNull(ProxyHead.parse("GET /relative HTTP/1.1\r\n\r\n"))
    }

    @Test
    fun sessionShowsItsProjectFolderName() {
        fun s(title: String, cwd: String) = com.neststudio.pocketshell.core.SessionInfo(
            "id1", "external", title, "pwsh", cwd, "running", "", "", 0, 0, null, null, "claude",
        )
        assertEquals("flowdesk", s("✳ 조작 가능한 모든 것 MCP", "E:\\AIWork\\flowdesk").displayTitle)
        assertEquals("pkshell-qa2", s("claude", "C:\\Users\\me\\pkshell-qa2\\").displayTitle)
        assertEquals("C:", s("x", "C:\\").displayTitle)
        assertEquals("제목", s("제목", "").displayTitle)
    }

    @Test
    fun basicCredentials() = assertEquals("Basic dTpw", ProxyHead.basic("u", "p"))

    @Test
    fun addressesAndBookmarks() {
        assertEquals("http://10.0.0.5:8080", WebBookmarks.normalize(" 10.0.0.5:8080 "))
        assertEquals("https://x.corp", WebBookmarks.normalize("https://x.corp"))
        assertNull(WebBookmarks.normalize("  "))
        var list = WebBookmarks.add(emptyList(), WebBookmark("Wiki", "http://w"))
        list = WebBookmarks.add(list, WebBookmark("Jira", "http://j"))
        list = WebBookmarks.add(list, WebBookmark("Wiki 2", "http://w"))
        assertEquals(listOf("Wiki 2", "Jira"), list.map { it.title })
        assertEquals(list, WebBookmarks.parse(WebBookmarks.toText(list)))
        assertTrue(WebBookmarks.remove(list, "http://w").none { it.url == "http://w" })
    }
}
