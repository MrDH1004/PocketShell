package com.neststudio.pocketshell

import com.neststudio.pocketshell.core.AgentEvents
import com.neststudio.pocketshell.core.PromptActions
import com.neststudio.pocketshell.core.PromptOption
import com.neststudio.pocketshell.core.QuietHours
import com.neststudio.pocketshell.core.Snippets
import com.neststudio.pocketshell.core.UpdateRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyRulesTest {

    @Test
    fun quietHoursMayPassMidnight() {
        val start = 23 * 60
        val end = 7 * 60
        assertTrue(QuietHours.contains(start, end, 23 * 60))
        assertTrue(QuietHours.contains(start, end, 2 * 60))
        assertFalse(QuietHours.contains(start, end, 7 * 60)) // the end is not part of it
        assertFalse(QuietHours.contains(start, end, 12 * 60))
        // A range within one day, and an empty one.
        assertTrue(QuietHours.contains(13 * 60, 14 * 60, 13 * 60 + 30))
        assertFalse(QuietHours.contains(13 * 60, 14 * 60, 15 * 60))
        assertFalse(QuietHours.contains(600, 600, 600))
        assertEquals("07:05", QuietHours.format(7 * 60 + 5))
    }

    @Test
    fun moreChoicesThanButtonsKeepTwoAndOfferToOpen() {
        fun options(n: Int) = (1..n).map { PromptOption(it.toString(), "choice $it") }
        val three = PromptActions.plan(options(3))
        assertEquals(listOf(0, 1, 2), three.options.map { it.index })
        assertFalse(three.openForMore)
        val five = PromptActions.plan(options(5))
        assertEquals(listOf("1", "2"), five.options.map { it.value.key })
        assertTrue(five.openForMore)
    }

    @Test
    fun snippetsAreOnePerLineWithoutBlanksOrRepeats() {
        assertEquals(listOf("계속해", "커밋해"), Snippets.parse("  계속해 \n\n커밋해\n계속해\n"))
        assertEquals(emptyList<String>(), Snippets.parse(" \n"))
        assertEquals(Snippets.MAX, Snippets.parse((1..40).joinToString("\n") { "p$it" }).size)
        assertEquals(Snippets.MAX_LENGTH, Snippets.parse("x".repeat(500)).single().length)
        assertEquals("a\nb", Snippets.toText(listOf("a", "b")))
    }

    @Test
    fun updateRefusalListsTheSessionsItWouldEnd() {
        val body = """{"error":"x","runningSessions":2,"workingAgents":1,"sessions":[
            {"id":"a1","title":"api","agent":"claude","agentState":"working"},
            {"id":"b2","title":"","agent":null,"agentState":null}]}"""
        val r = UpdateRefusal.parse(body)
        assertEquals(2, r.running)
        assertEquals(1, r.working)
        assertEquals(listOf("api", "b2"), r.sessions.map { it.title })
        assertEquals("working", r.sessions[0].agentState)
        assertNull(r.sessions[1].agentState)
        // A host before 0.2.61 only gives the count; nonsense gives nothing.
        val old = UpdateRefusal.parse("""{"error":"x","runningSessions":3}""")
        assertEquals(3, old.running)
        assertEquals(0, old.working)
        assertTrue(old.sessions.isEmpty())
        assertNull(UpdateRefusal.parse("not json").running)
    }

    @Test
    fun doneEventCarriesThePreview() {
        val json = """{"epoch":"e","seq":2,"events":[
            {"seq":1,"sessionId":"s","title":"t","agent":"claude","state":"done","prompt":null,"preview":"All tests pass."},
            {"seq":2,"sessionId":"s","title":"t","agent":"claude","state":"done","prompt":null}]}"""
        val events = AgentEvents.parse(json).events
        assertEquals("All tests pass.", events[0].preview)
        assertNull(events[1].preview)
    }
}
