package com.neststudio.pocketshell

import com.neststudio.pocketshell.core.ClientProtocol
import com.neststudio.pocketshell.core.ServerMessage
import com.neststudio.pocketshell.core.SessionAction
import com.neststudio.pocketshell.core.SessionActions
import com.neststudio.pocketshell.core.SessionInfo
import com.neststudio.pocketshell.core.SessionSections
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    private val sessionJson = """{"id":"0123456789ab","kind":"host","title":"작업","shell":"pwsh","cwd":"C:\\Users\\me",
        "state":"running","createdAt":"2026-11-04T10:00:00Z","lastActivity":"2026-11-04T10:05:00Z","cols":80,"rows":24,
        "clients":[{"id":"c1","type":"cli","cols":120,"rows":30}],"pid":null,"resumedFrom":null}"""

    @Test
    fun parsesControlMessages() {
        assertEquals(ServerMessage.Size(100, 40), ServerMessage.parse("""{"type":"size","cols":100,"rows":40}"""))
        assertEquals(ServerMessage.Exit(3), ServerMessage.parse("""{"type":"exit","code":3}"""))
        assertEquals(ServerMessage.ReplayEnd, ServerMessage.parse("""{"type":"replay-end"}"""))
        assertEquals(ServerMessage.Unknown("future"), ServerMessage.parse("""{"type":"future","x":1}"""))
        val hello = ServerMessage.parse("""{"type":"hello","session":$sessionJson}""") as ServerMessage.Hello
        assertEquals("0123456789ab", hello.session!!.id)
        assertEquals("작업", hello.session!!.title)
        assertEquals("C:\\Users\\me", hello.session!!.cwd)
    }

    @Test
    fun ignoresNonControlText() {
        assertNull(ServerMessage.parse("hello"))
        assertNull(ServerMessage.parse("""{"cols":1}"""))
        assertNull(ServerMessage.parse("[1,2]"))
    }

    @Test
    fun sessionListIsSplitIntoSections() {
        val text = """{"sessions":[
            {"id":"aaaaaaaaaaaa","kind":"host","title":"a","shell":"pwsh","cwd":"C:\\","state":"running","lastActivity":"2026-11-04T10:00:00Z"},
            {"id":"ext-4242","kind":"external","title":"cmd","shell":"cmd","cwd":"","state":"running","pid":4242},
            {"id":"bbbbbbbbbbbb","kind":"history","title":"b","shell":"cmd","cwd":"D:\\","state":"exited","lastActivity":"2026-11-03T10:00:00Z"},
            {"id":"cccccccccccc","kind":"host","title":"c","shell":"bash","cwd":"/","state":"running","lastActivity":"2026-11-04T11:00:00Z"}
        ]}"""
        val s = SessionSections.of(SessionInfo.listFromJson(text))
        assertEquals(listOf("cccccccccccc", "aaaaaaaaaaaa"), s.running.map { it.id })
        assertEquals(listOf("ext-4242"), s.external.map { it.id })
        assertEquals(4242, s.external[0].pid)
        assertEquals(listOf("bbbbbbbbbbbb"), s.recent.map { it.id })
    }

    @Test
    fun recentMergesClosedConsolesNewestFirstAndIsLimited() {
        val text = """{"sessions":[
            {"id":"bbbbbbbbbbbb","kind":"history","title":"b","shell":"cmd","cwd":"D:\\","state":"exited","lastActivity":"2026-11-03T10:00:00.000Z"},
            {"id":"rec-111111111111","kind":"recent","title":"proj","shell":"cmd","cwd":"E:\\proj","state":"exited","lastActivity":"2026-11-05T10:00:00.000Z","agent":"claude"},
            {"id":"rec-222222222222","kind":"recent","title":"old","shell":"cmd","cwd":"E:\\old","state":"exited","lastActivity":"2026-11-01T10:00:00.000Z"}
        ]}"""
        val all = SessionInfo.listFromJson(text)
        assertTrue(all.all { it.isHistory })
        assertEquals(listOf("rec-111111111111", "bbbbbbbbbbbb", "rec-222222222222"), SessionSections.of(all, 10).recent.map { it.id })
        assertEquals(listOf("rec-111111111111", "bbbbbbbbbbbb"), SessionSections.of(all, 2).recent.map { it.id })
        assertTrue(SessionSections.of(all, 0).recent.isEmpty())
        assertEquals("claude", all[1].agent)
    }

    @Test
    fun favoritesSitAboveRecentOncePerFolder() {
        val text = """{"sessions":[
            {"id":"rec-a","kind":"recent","title":"a","shell":"cmd","cwd":"E:\\a","state":"exited","lastActivity":"2026-11-14T01:00:00.000Z"},
            {"id":"rec-b","kind":"recent","title":"b","shell":"cmd","cwd":"E:\\b","state":"exited","lastActivity":"2026-11-14T02:00:00.000Z"},
            {"id":"hhhhhhhhhhhh","kind":"history","title":"c","shell":"cmd","cwd":"E:\\c","state":"exited","lastActivity":"2026-11-14T03:00:00.000Z"},
            {"id":"live-c","kind":"host","title":"c","shell":"cmd","cwd":"e:\\C\\","state":"running","lastActivity":"2026-11-14T04:00:00.000Z"}
        ],"favorites":["E:\\c","e:\\B\\","E:\\gone"]}"""
        val favs = SessionInfo.favoritesFromJson(text)
        val s = SessionSections.of(SessionInfo.listFromJson(text), favorites = favs)
        // starred order; a folder open above (live-c) is not repeated; else the newest record, else a stub that starts one
        assertEquals(listOf("rec-b", "fav:e:\\gone"), s.favorites.map { it.id })
        assertEquals(listOf("live-c"), s.running.map { it.id })
        assertTrue(s.favorites[1].isFavoriteStub)
        assertEquals(listOf(SessionAction.FAVORITE), SessionActions.of(s.favorites[1]))
        // starred folders leave 최근
        assertEquals(listOf("rec-a"), s.recent.map { it.id })
        assertTrue(SessionSections.isFavorite(favs, "E:\\B"))
        assertTrue(SessionActions.of(s.recent[0]).contains(SessionAction.FAVORITE))
        assertTrue(SessionInfo.favoritesFromJson("""{"sessions":[]}""").isEmpty()) // older host
    }

    @Test
    fun recentShowsEveryFolderOnce() {
        val text = """{"sessions":[
            {"id":"rec-old","kind":"recent","title":"vailvpn","shell":"cmd","cwd":"E:\\vailvpn","state":"exited","lastActivity":"2026-11-14T01:00:00.000Z","agent":"claude"},
            {"id":"rec-new","kind":"recent","title":"vailvpn","shell":"cmd","cwd":"e:\\VailVpn\\","state":"exited","lastActivity":"2026-11-14T03:08:00.000Z","agent":"claude"},
            {"id":"rec-gpt","kind":"recent","title":"vailvpn","shell":"cmd","cwd":"E:\\vailvpn","state":"exited","lastActivity":"2026-11-13T10:00:00.000Z","agent":"gpt"},
            {"id":"aaaaaaaaaaaa","kind":"history","title":"a","shell":"cmd","cwd":"","state":"exited","lastActivity":"2026-11-12T10:00:00.000Z"},
            {"id":"cccccccccccc","kind":"history","title":"c","shell":"cmd","cwd":"","state":"exited","lastActivity":"2026-11-11T10:00:00.000Z"},
            {"id":"rec-open","kind":"recent","title":"shell","shell":"cmd","cwd":"E:\\PocketShell","state":"exited","lastActivity":"2026-11-14T05:00:00.000Z"},
            {"id":"ext-42","kind":"external","title":"shell","shell":"cmd","cwd":"E:\\PocketShell\\","state":"running","lastActivity":"2026-11-14T05:30:00.000Z","pid":42},
            {"id":"hist-open","kind":"history","title":"h","shell":"pwsh","cwd":"D:\\work","state":"exited","lastActivity":"2026-11-14T04:00:00.000Z"},
            {"id":"dddddddddddd","kind":"host","title":"h","shell":"pwsh","cwd":"d:\\WORK","state":"running","lastActivity":"2026-11-14T05:10:00.000Z"}
        ]}"""
        val s = SessionSections.of(SessionInfo.listFromJson(text), 10)
        // every folder once (its newest record, whatever the AI or shell), newest first; folders open above too;
        // records without a folder are each their own
        assertEquals(listOf("rec-open", "hist-open", "rec-new", "aaaaaaaaaaaa", "cccccccccccc"), s.recent.map { it.id })
        assertEquals(5, SessionSections.of(SessionInfo.listFromJson(text)).recent.size) // default: no limit
        assertEquals(listOf("dddddddddddd"), s.running.map { it.id })
        assertEquals(listOf("ext-42"), s.external.map { it.id })
    }

    @Test
    fun plainCmdConsolesAreNotListed() {
        val text = """{"sessions":[
            {"id":"ext-1","kind":"external","title":"C:\\WINDOWS\\System32\\cmd.exe","shell":"cmd","cwd":"C:\\a","state":"running","pid":1},
            {"id":"ext-2","kind":"external","title":"관리자: C:\\Windows\\system32\\cmd.exe","shell":"cmd","cwd":"C:\\b","state":"running","pid":2},
            {"id":"ext-3","kind":"external","title":"✳ VeilVPN","shell":"cmd","cwd":"C:\\c","state":"running","pid":3},
            {"id":"rec-1","kind":"recent","title":"C:\\WINDOWS\\System32\\cmd.exe","shell":"cmd","cwd":"C:\\d","state":"exited","lastActivity":"2026-11-13T08:32:00.000Z","agent":"gpt"},
            {"id":"rec-2","kind":"recent","title":"Tapo_P110","shell":"cmd","cwd":"C:\\e","state":"exited","lastActivity":"2026-11-13T08:35:00.000Z"}
        ]}"""
        val s = SessionSections.of(SessionInfo.listFromJson(text), 10)
        assertEquals(listOf("ext-3"), s.external.map { it.id })
        assertEquals(listOf("rec-2"), s.recent.map { it.id })
    }

    @Test
    fun system32ConsolesAreNotListed() {
        val text = """{"sessions":[
            {"id":"ext-1","kind":"external","title":"관리자: Windows PowerShell","shell":"powershell","cwd":"C:\\WINDOWS\\system32","state":"running","pid":1},
            {"id":"ext-2","kind":"external","title":"✳ Claude","shell":"cmd","cwd":"C:\\Windows\\System32\\","state":"running","pid":2,"agent":"claude"},
            {"id":"ext-3","kind":"external","title":"drivers","shell":"cmd","cwd":"C:\\Windows\\System32\\drivers","state":"running","pid":3},
            {"id":"rec-1","kind":"recent","title":"system32","shell":"powershell","cwd":"C:\\WINDOWS\\system32","state":"exited","lastActivity":"2026-11-16T02:56:33.000Z"},
            {"id":"rec-2","kind":"recent","title":"etc","shell":"cmd","cwd":"C:\\GIT\\etc","state":"exited","lastActivity":"2026-11-16T01:00:00.000Z"}
        ]}"""
        val s = SessionSections.of(SessionInfo.listFromJson(text), 10)
        assertEquals(listOf("ext-2", "ext-3"), s.external.map { it.id }) // an AI running there, and a folder below, still show
        assertEquals(listOf("rec-2"), s.recent.map { it.id })
    }

    @Test
    fun resizeMessageAndUrl() {
        val o = JSONObject(ClientProtocol.resizeMessage(90, 33))
        assertEquals("resize", o.getString("type"))
        assertEquals(90, o.getInt("cols"))
        assertEquals(33, o.getInt("rows"))

        val url = ClientProtocol.webSocketUrl("192.168.0.10", 7380, "0123456789ab", 90, 33, openPcTab = true)
        assertEquals("wss://192.168.0.10:7380/ws/sessions/0123456789ab?client=android&cols=90&rows=33&openPcTab=1", url)
        assertFalse(url.contains("token"))
        assertTrue(ClientProtocol.webSocketUrl("fd7a::1", 7380, "x", 1, 1, true).startsWith("wss://[fd7a::1]:7380/"))
    }

    @Test
    fun longPressOffersWhatFitsTheSession() {
        fun session(kind: String, state: String, folder: Boolean, shares: Int = 0) = SessionInfo.fromJson(
            JSONObject(sessionJson).put("kind", kind).put("state", state).put("folderDeletable", folder).put("shares", shares),
        )
        val git = com.neststudio.pocketshell.core.SessionAction.GIT
        val end = com.neststudio.pocketshell.core.SessionAction.END
        val term = com.neststudio.pocketshell.core.SessionAction.NEW_TERMINAL
        val delete = com.neststudio.pocketshell.core.SessionAction.DELETE
        val folder = com.neststudio.pocketshell.core.SessionAction.DELETE_WITH_FOLDER
        val fav = com.neststudio.pocketshell.core.SessionAction.FAVORITE
        val of = com.neststudio.pocketshell.core.SessionActions::of
        // Running: end first (the record stays), then the folder's git changes, or delete; the folder only when
        // the PC allows it. Sharing is not offered (removed in 0.2.108), also when an older app shared it.
        assertEquals(listOf(end, git, fav, delete), of(session("host", "running", false)))
        assertEquals(listOf(end, git, fav, delete, folder), of(session("host", "running", true)))
        assertEquals(listOf(end, git, fav, delete), of(session("host", "running", false, shares = 2)))
        // A record cannot be ended any more.
        assertEquals(listOf(git, fav, delete, folder), of(session("history", "exited", true)))
        assertEquals(listOf(git, fav, delete), of(session("recent", "exited", false)))
        // A console opened on the PC: ended, a new terminal in its folder; never deleted (and never its folder).
        assertEquals(listOf(end, term, git, fav), of(session("external", "running", true)))
        // No folder: nothing to look at.
        assertEquals(listOf(end, term), of(SessionInfo.fromJson(JSONObject(sessionJson).put("kind", "external").put("cwd", ""))))
        // An older PC does not send the fields.
        assertFalse(SessionInfo.fromJson(JSONObject(sessionJson)).folderDeletable)
        assertEquals(0, SessionInfo.fromJson(JSONObject(sessionJson)).shares)
    }
}
