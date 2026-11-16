package com.neststudio.pocketshell

import com.neststudio.pocketshell.core.AgentState
import com.neststudio.pocketshell.core.AgentSummary
import com.neststudio.pocketshell.core.BoardTotals
import com.neststudio.pocketshell.core.PcStatus
import com.neststudio.pocketshell.core.SessionInfo
import com.neststudio.pocketshell.core.ShareTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareAndBoardTest {
    private fun session(
        id: String,
        kind: String = SessionInfo.KIND_HOST,
        state: String = SessionInfo.STATE_RUNNING,
        agent: String? = null,
        agentState: String? = null,
        last: String = "2026-11-16T10:00:00Z",
    ) = SessionInfo(id, kind, "", "pwsh", "E:\\w\\$id", state, "", last, 80, 24, null, null, agent, agentState)

    @Test
    fun sharedTextTakesTheSubjectWhenItAddsSomething() {
        assertEquals("Page title https://x.dev/a", ShareTargets.text("https://x.dev/a", "Page title"))
        assertEquals("hello world", ShareTargets.text("hello world", "hello"))   // subject already leads the text
        assertEquals("only subject", ShareTargets.text(null, " only subject "))
        assertEquals("a\nb", ShareTargets.text("a\r\nb\r\n", null))              // no trailing newline: nothing submitted
        assertNull(ShareTargets.text("  ", ""))
    }

    @Test
    fun longTextIsCutWithoutSplittingAnEmoji() {
        val text = "a".repeat(ShareTargets.MAX_TEXT - 1) + "😀" + "tail"
        val fitted = ShareTargets.fit(text)
        assertEquals(ShareTargets.MAX_TEXT - 1, fitted.length)
        assertEquals("ok", ShareTargets.fit("ok"))
    }

    @Test
    fun liveSessionsOnlyWaitingAiFirst() {
        val list = listOf(
            session("shell", last = "2026-11-16T12:00:00Z"),
            session("old", state = SessionInfo.STATE_EXITED),
            session("hist", kind = SessionInfo.KIND_HISTORY),
            session("ext", kind = SessionInfo.KIND_EXTERNAL, agent = "claude", last = "2026-11-16T09:00:00Z"),
            session("busy", agentState = AgentState.WORKING, last = "2026-11-16T11:00:00Z"),
            session("ask", agentState = AgentState.WAITING, last = "2026-11-16T08:00:00Z"),
        )
        assertEquals(listOf("ask", "busy", "ext", "shell"), ShareTargets.order(list).map { it.id })
    }

    @Test
    fun boardAddsUpOnlinePcsAndNamesTheOthers() {
        val pcs = listOf("a" to "집", "b" to "회사", "c" to "노트북")
        val statuses = mapOf(
            "a" to PcStatus(AgentSummary(1, 2, 0), 2000),
            "b" to PcStatus(null, 1000),
        )
        val t = BoardTotals.of(pcs, statuses)
        assertEquals(Triple(1, 2, 0), Triple(t.working, t.waiting, t.done))
        assertEquals(listOf("집"), t.online)
        assertEquals(listOf("회사"), t.offline)
        assertEquals(listOf("노트북"), t.unknown)
        assertEquals(1000L, t.oldest)
    }

    @Test
    fun pcStatusRoundTrips() {
        val on = PcStatus(AgentSummary(3, 1, 2), 42)
        assertEquals(on, PcStatus.fromJson(on.toJson()))
        val off = PcStatus(null, 7)
        assertEquals(off, PcStatus.fromJson(off.toJson()))
        assertNull(PcStatus.fromJson("not json"))
    }
}
