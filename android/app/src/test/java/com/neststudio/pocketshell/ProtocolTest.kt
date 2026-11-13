package com.neststudio.pocketshell

import com.neststudio.pocketshell.core.ClientProtocol
import com.neststudio.pocketshell.core.ServerMessage
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
    fun recentShowsOnlyTheNewestPerFolder() {
        val text = """{"sessions":[
            {"id":"rec-old","kind":"recent","title":"vailvpn","shell":"cmd","cwd":"E:\\vailvpn","state":"exited","lastActivity":"2026-11-14T01:00:00.000Z","agent":"claude"},
            {"id":"rec-new","kind":"recent","title":"vailvpn","shell":"cmd","cwd":"e:\\VailVpn\\","state":"exited","lastActivity":"2026-11-14T03:08:00.000Z","agent":"claude"},
            {"id":"rec-gpt","kind":"recent","title":"vailvpn","shell":"cmd","cwd":"E:\\vailvpn","state":"exited","lastActivity":"2026-11-13T10:00:00.000Z","agent":"gpt"},
            {"id":"aaaaaaaaaaaa","kind":"history","title":"a","shell":"cmd","cwd":"","state":"exited","lastActivity":"2026-11-12T10:00:00.000Z"},
            {"id":"cccccccccccc","kind":"history","title":"c","shell":"cmd","cwd":"","state":"exited","lastActivity":"2026-11-11T10:00:00.000Z"}
        ]}"""
        val recent = SessionSections.of(SessionInfo.listFromJson(text), 10).recent.map { it.id }
        assertEquals(listOf("rec-new", "rec-gpt", "aaaaaaaaaaaa", "cccccccccccc"), recent)
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
        fun session(kind: String, state: String, folder: Boolean) = SessionInfo.fromJson(
            JSONObject(sessionJson).put("kind", kind).put("state", state).put("folderDeletable", folder),
        )
        val end = com.neststudio.pocketshell.core.SessionAction.END
        val delete = com.neststudio.pocketshell.core.SessionAction.DELETE
        val folder = com.neststudio.pocketshell.core.SessionAction.DELETE_WITH_FOLDER
        val of = com.neststudio.pocketshell.core.SessionActions::of
        // Running: end (the record stays) or delete; the folder only when the PC allows it.
        assertEquals(listOf(end, delete), of(session("host", "running", false)))
        assertEquals(listOf(end, delete, folder), of(session("host", "running", true)))
        // A record cannot be ended any more.
        assertEquals(listOf(delete, folder), of(session("history", "exited", true)))
        assertEquals(listOf(delete), of(session("recent", "exited", false)))
        // A console opened on the PC: only ended, never deleted (and never its folder).
        assertEquals(listOf(end), of(session("external", "running", true)))
        // An older PC does not send the field.
        assertFalse(SessionInfo.fromJson(JSONObject(sessionJson)).folderDeletable)
    }
}
