package com.neststudio.pocketshell.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PatchNotesTest {
    private val md = """
        # PocketShell 패치내역

        > 설명

        ## v0.2.109 (2026-11-21)
        - 터미널 기록 양
        - **패치내역**
          두 줄로 이어짐

        ## v0.2.107 (2026-11-20)
        - RAM GB

        ## v0.2.108
        - 공유 제거
    """.trimIndent()

    private fun v(s: String) = SemVer.parse(s)!!

    @Test
    fun parsesSectionsNewestFirst() {
        val e = PatchNotes.parse(md)
        assertEquals(listOf("0.2.109", "0.2.108", "0.2.107"), e.map { it.version.toString() })
        assertEquals("2026-11-21", e[0].date)
        assertEquals(listOf("터미널 기록 양", "패치내역 두 줄로 이어짐"), e[0].items)
        assertEquals("", e[1].date)
    }

    @Test
    fun sinceShowsWhatIsNewUpToThisVersion() {
        val e = PatchNotes.parse(md)
        assertEquals(listOf("0.2.109", "0.2.108"), PatchNotes.since(e, v("0.2.107"), v("0.2.109")).map { it.version.toString() })
        // First run with the feature (nothing seen yet): only this version.
        assertEquals(listOf("0.2.109"), PatchNotes.since(e, null, v("0.2.109")).map { it.version.toString() })
        // Notes already ahead of the installed app are not shown.
        assertEquals(listOf("0.2.108"), PatchNotes.since(e, v("0.2.107"), v("0.2.108")).map { it.version.toString() })
        assertTrue(PatchNotes.since(e, v("0.2.109"), v("0.2.109")).isEmpty())
    }

    @Test
    fun formatsForTheDialog() {
        val e = PatchNotes.parse(md).take(1)
        assertEquals("v0.2.109 (2026-11-21)\n• 터미널 기록 양\n• 패치내역 두 줄로 이어짐", PatchNotes.format(e))
    }

    @Test
    fun repositoryFileParses() {
        // The real PATCHNOTES.md the app fetches: every section must parse (a typo would hide it).
        val f = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "PATCHNOTES.md") }.firstOrNull { it.isFile }
            ?: return
        val text = f.readText()
        val headings = text.lines().count { it.startsWith("## ") }
        val e = PatchNotes.parse(text)
        assertEquals(headings, e.size)
        assertTrue(e.all { it.items.isNotEmpty() })
    }

    @Test
    fun terminalHistoryUrlAndDefault() {
        assertEquals(TerminalHistory.NORMAL, TerminalHistory.of(null))
        assertEquals(TerminalHistory.SMALL, TerminalHistory.of("small"))
        val url = ClientProtocol.webSocketUrl("h", 1, "s", 80, 24, true, TerminalHistory.SMALL)
        assertTrue(url.endsWith("&openPcTab=1&replayKb=256&historyLines=500"))
        assertTrue(ClientProtocol.webSocketUrl("h", 1, "s", 80, 24, true, TerminalHistory.ALL).endsWith("&openPcTab=1"))
    }
}
