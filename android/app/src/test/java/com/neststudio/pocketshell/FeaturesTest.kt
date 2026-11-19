package com.neststudio.pocketshell

import com.neststudio.pocketshell.core.DiffLineKind
import com.neststudio.pocketshell.core.DiffLines
import com.neststudio.pocketshell.core.GitStatus
import com.neststudio.pocketshell.core.PairLink
import com.neststudio.pocketshell.core.SessionInfo
import com.neststudio.pocketshell.core.SessionSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeaturesTest {
    private val fp = "ab".repeat(32)

    @Test
    fun pairLinkRoundTrip() {
        val l = PairLink(listOf("192.168.0.10", "100.64.0.2"), 47999, "123456", fp, "내 프로젝트 & co")
        val back = PairLink.parse(l.toUri())
        assertEquals(l, back)
        assertEquals("192.168.0.10:47999", l.firstAddress)
        assertEquals("192.168.0.10", l.copy(port = 7380).firstAddress)
        assertEquals("[fd7a::1]", l.copy(hosts = listOf("fd7a::1"), port = 7380).firstAddress)
    }

    @Test
    fun pairLinkRejectsWhatIsNotOne() {
        assertNull(PairLink.parse("https://example.com/?h=1.2.3.4&pin=123456"))
        assertNull(PairLink.parse("pocketshell://pair?h=1.2.3.4&pin=12345"))
        assertNull(PairLink.parse("pocketshell://pair?pin=123456"))
        assertNull(PairLink.parse("pocketshell://pair?h=1.2.3.4&pin=123456&p=99999"))
        assertNull(PairLink.parse(null))
        assertNull(PairLink.parse("not a link"))
        // A bad fingerprint is dropped (the user is asked to compare instead), the rest kept.
        assertNull(PairLink.parse("pocketshell://pair?h=1.2.3.4&pin=123456&fp=zz")!!.fingerprint)
        assertEquals(7380, PairLink.parse("pocketshell://pair?h=1.2.3.4&pin=123%20456")!!.port)
    }

    @Test
    fun shortcutOpensTheLiveSessionInTheFolderElseTheNewestRecord() {
        val text = """{"sessions":[
            {"id":"rec-1","kind":"recent","title":"a","shell":"cmd","cwd":"E:\\proj","state":"exited","lastActivity":"2026-11-17T01:00:00.000Z"},
            {"id":"hist-2","kind":"history","title":"a","shell":"cmd","cwd":"e:\\Proj\\","state":"exited","lastActivity":"2026-11-17T02:00:00.000Z"},
            {"id":"ext-9","kind":"external","title":"a","shell":"cmd","cwd":"E:\\other","state":"running","pid":9},
            {"id":"aaaaaaaaaaaa","kind":"host","title":"a","shell":"cmd","cwd":"E:\\proj","state":"running","lastActivity":"2026-11-17T00:30:00.000Z"}
        ]}"""
        val all = SessionInfo.listFromJson(text)
        assertEquals("aaaaaaaaaaaa", SessionSections.forFolder(all, "E:\\PROJ\\")?.id)
        assertEquals("hist-2", SessionSections.forFolder(all.filter { it.id != "aaaaaaaaaaaa" }, "E:\\proj")?.id)
        assertEquals("ext-9", SessionSections.forFolder(all, "E:\\other")?.id)
        assertNull(SessionSections.forFolder(all, "E:\none"))
        assertNull(SessionSections.forFolder(all, ""))
    }

    @Test
    fun gitStatusParses() {
        val s = GitStatus.parse("""{"root":"E:\\p","branch":"main","upstream":"origin/main","ahead":1,"behind":0,
            "files":[{"path":"src/a.kt","status":"M","staged":false,"added":3,"removed":1},
                     {"path":"new.txt","status":"?","staged":false,"added":null,"removed":null},
                     {"path":"b.kt","from":"old/b.kt","status":"R","staged":true,"added":0,"removed":0}],"truncated":false}""")
        assertEquals("main", s.branch)
        assertEquals(3, s.files.size)
        assertEquals("a.kt", s.files[0].name)
        assertEquals("src", s.files[0].folder)
        assertEquals(true, s.files[1].isUntracked)
        assertNull(s.files[1].added)
        assertEquals("old/b.kt", s.files[2].from)
        assertEquals(3, s.totalAdded)
        assertEquals(1, s.totalRemoved)
    }

    @Test
    fun diffLineKinds() {
        assertEquals(DiffLineKind.META, DiffLines.kind("+++ b/a.kt"))
        assertEquals(DiffLineKind.META, DiffLines.kind("--- a/a.kt"))
        assertEquals(DiffLineKind.META, DiffLines.kind("diff --git a/a b/a"))
        assertEquals(DiffLineKind.HUNK, DiffLines.kind("@@ -1,2 +1,3 @@ fun x()"))
        assertEquals(DiffLineKind.ADD, DiffLines.kind("+val y = 2"))
        assertEquals(DiffLineKind.DEL, DiffLines.kind("-val y = 1"))
        assertEquals(DiffLineKind.CONTEXT, DiffLines.kind(" val z = 3"))
        assertEquals(DiffLineKind.META, DiffLines.kind("\\ No newline at end of file"))
    }
}

class SessionTimeTest {
    private val zone = java.time.ZoneId.of("Asia/Seoul")
    private val now = java.time.Instant.parse("2026-11-17T12:00:00Z") // 21:00 KST

    private fun at(iso: String) = com.neststudio.pocketshell.core.SessionTime.short(java.time.Instant.parse(iso), now, zone)

    @org.junit.Test
    fun shortTimes() {
        org.junit.Assert.assertEquals("21:25", at("2026-11-17T12:25:00Z"))
        org.junit.Assert.assertEquals("00:10", at("2026-11-16T15:10:00Z")) // just after midnight KST = today
        org.junit.Assert.assertEquals("어제 22:56", at("2026-11-16T13:56:00Z"))
        org.junit.Assert.assertEquals("11/15 10:25", at("2026-11-15T01:25:00Z"))
        org.junit.Assert.assertEquals("2025/3/2", at("2025-03-02T01:00:00Z"))
    }
}

class FolderHistoryTest {
    @org.junit.Test
    fun backForwardLikeExplorer() {
        val h = com.neststudio.pocketshell.core.FolderHistory()
        org.junit.Assert.assertFalse(h.canBack)
        h.visit("C:/p", "C:/p/a")
        h.visit("C:/p/a", "C:/p/a/b")
        org.junit.Assert.assertEquals("C:/p/a", h.back("C:/p/a/b"))
        org.junit.Assert.assertEquals("C:/p", h.back("C:/p/a"))
        org.junit.Assert.assertNull(h.back("C:/p"))
        org.junit.Assert.assertEquals("C:/p/a", h.forward("C:/p"))
        // going somewhere new drops forward
        h.visit("C:/p/a", "C:/p/c")
        org.junit.Assert.assertFalse(h.canForward)
        org.junit.Assert.assertEquals("C:/p/a", h.back("C:/p/c"))
        h.visit("x", "x") // same folder: no step
        val (b, f) = h.save()
        val r = com.neststudio.pocketshell.core.FolderHistory().apply { restore(b, f) }
        org.junit.Assert.assertEquals("C:/p", r.back("C:/p/a"))
        org.junit.Assert.assertEquals("C:/p/a", r.forward("C:/p"))
    }
}

class TerminalLinksTest {
    private val L = com.neststudio.pocketshell.core.TerminalLinks

    @org.junit.Test
    fun devServerAddresses() {
        val vite = "  ➜  Local:   http://localhost:5173/"
        org.junit.Assert.assertEquals("http://localhost:5173/", L.at(vite, vite.indexOf("local") + 3))
        org.junit.Assert.assertEquals("http://localhost:5173/", L.at(vite, 0)) // the only one on the line
        org.junit.Assert.assertEquals("http://localhost:3000", L.at("ready on 0.0.0.0:3000.", 12))
        org.junit.Assert.assertEquals("http://127.0.0.1:8000/docs", L.at("Uvicorn running on 127.0.0.1:8000/docs (Press CTRL+C)", 25))
        org.junit.Assert.assertEquals("https://example.com/a_(b)", L.at("see https://example.com/a_(b), ok", 10))
        org.junit.Assert.assertNull(L.at("no address here 12:30", 5))
        org.junit.Assert.assertNull(L.at("version 1.2.3.4", 9))
    }

    @org.junit.Test
    fun picksTheOneUnderTheTouchCountingWideCharacters() {
        val line = "서버 http://a.test:1 와 http://b.test:2"
        val wide = { cp: Int -> if (cp >= 0xAC00) 2 else 1 }
        // "서버 " is 5 columns wide: column 6 is inside the first address
        org.junit.Assert.assertEquals("http://a.test:1", L.at(line, 6, wide))
        val second = 5 + "http://a.test:1".length + 1 + 2 + 1 + 3
        org.junit.Assert.assertEquals("http://b.test:2", L.at(line, second, wide))
        org.junit.Assert.assertNull(L.at(line, 3, wide)) // two addresses, touch on neither
    }
}
