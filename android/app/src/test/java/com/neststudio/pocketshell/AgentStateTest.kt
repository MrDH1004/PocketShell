package com.neststudio.pocketshell

import com.neststudio.pocketshell.core.AgentEvents
import com.neststudio.pocketshell.core.AgentState
import com.neststudio.pocketshell.core.AgentSummary
import com.neststudio.pocketshell.core.EventCursor
import com.neststudio.pocketshell.core.SessionInfo
import com.neststudio.pocketshell.core.TerminalText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStateTest {
    private fun reply(epoch: String, seq: Long, vararg eventSeqs: Long): AgentEvents {
        val events = eventSeqs.joinToString(",") { """{"seq":$it,"sessionId":"s$it","title":"t","agent":"claude","state":"done"}""" }
        return AgentEvents.parse("""{"epoch":"$epoch","seq":$seq,"events":[$events]}""")
    }

    @Test
    fun firstContactOnlyLearnsThePositionAndAnnouncesNothing() {
        val step = EventCursor.START.accept(reply("e1", 7, 6, 7))
        assertEquals(EventCursor("e1", 7), step.cursor)
        assertTrue(step.announce.isEmpty())
    }

    @Test
    fun laterRepliesAnnounceOnlyNewerEvents() {
        val step = EventCursor("e1", 7).accept(reply("e1", 9, 7, 8, 9))
        assertEquals(listOf(8L, 9L), step.announce.map { it.seq })
        assertEquals(EventCursor("e1", 9), step.cursor)
        // A timeout reply carries no events and keeps the cursor.
        assertEquals(EventCursor("e1", 9), EventCursor("e1", 9).accept(reply("e1", 9)).cursor)
    }

    @Test
    fun hostRestartStartsOverFromItsNewLog() {
        val step = EventCursor("e1", 40).accept(reply("e2", 2))
        assertEquals(EventCursor("e2", 0), step.cursor)
        assertTrue(step.announce.isEmpty())
        assertEquals(listOf(1L, 2L), step.cursor.accept(reply("e2", 2, 1, 2)).announce.map { it.seq })
    }

    @Test
    fun eventAndSessionCarryThePrompt() {
        val json = """{"epoch":"e","seq":1,"events":[{"seq":1,"sessionId":"ext-10","title":"proj","agent":"claude","state":"waiting",
            "prompt":{"question":"Do you want to proceed?","options":[{"key":"1","label":"Yes"},{"key":"2","label":"No"}]}}]}"""
        val e = AgentEvents.parse(json).events.single()
        assertEquals(AgentState.WAITING, e.state)
        assertEquals("Do you want to proceed?", e.prompt!!.question)
        assertEquals(listOf("1", "2"), e.prompt!!.options.map { it.key })
        assertEquals(listOf(false, false), e.prompt!!.options.map { it.enter })

        // An arrow list: the highlighted choice is Enter alone, the next one arrow down + Enter.
        val arrows = SessionInfo.fromJson(JSONObject("""{"id":"a","kind":"host","agentState":"waiting","prompt":{"question":"Trust?",
            "options":[{"key":"","enter":true,"label":"No, exit"},{"key":"\u001b[B","enter":true,"label":"Yes"},{"key":"","label":"broken"}]}}"""))
        assertEquals(listOf("No, exit", "Yes"), arrows.prompt!!.options.map { it.label })
        assertEquals("\u001b[B", arrows.prompt!!.options[1].key)
        assertTrue(arrows.prompt!!.options.all { it.enter })

        val s = SessionInfo.fromJson(JSONObject("""{"id":"a","kind":"host","agent":"claude","agentState":"working"}"""))
        assertEquals(AgentState.WORKING, s.agentState)
        assertNull(s.prompt)
        // Hosts before 0.2.59 send neither field.
        assertNull(SessionInfo.fromJson(JSONObject("""{"id":"a","kind":"host"}""")).agentState)
        assertEquals(AgentSummary(1, 2, 0), AgentSummary.fromJson(JSONObject("""{"working":1,"waiting":2,"done":0}""")))
        assertNull(AgentSummary.fromJson(null))
    }

    @Test
    fun terminalTextStripsJoinsAndFinds() {
        val esc = "\u001b"
        assertEquals("❯ 1. Yes", TerminalText.strip("$esc[1;32m❯ 1. Yes$esc[0m\r"))
        assertEquals("a\n\nb", TerminalText.join(listOf("a   ", "", "b", "  ", "")))
        assertEquals("c\nd", TerminalText.join(listOf("a", "b", "c", "d"), maxLines = 2))
        val text = "Error: x\nok\nerror again"
        assertEquals(listOf(0, 12), TerminalText.find(text, "error"))
        assertTrue(TerminalText.find(text, " ").isEmpty())
        assertEquals(1, TerminalText.firstShown(listOf(0, 12)))
        assertEquals(0, TerminalText.step(1, 2, forward = true))
        assertEquals(1, TerminalText.step(0, 2, forward = false))
        assertEquals(-1, TerminalText.step(0, 0, forward = true))
    }
}
