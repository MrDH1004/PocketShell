package com.neststudio.pocketshell

import com.neststudio.pocketshell.core.HostUpdateStatus
import com.neststudio.pocketshell.core.UpdatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateStatusTest {

    @Test
    fun parsesInfoUpdate() {
        val info = """{"name":"PC","version":"0.1.1-alpha","fp":"x","port":7380,"hosts":[],
            "update":{"current":"0.1.1-alpha","latest":"0.1.2-alpha","state":"ready"}}"""
        val s = HostUpdateStatus.fromInfo(info)!!
        assertEquals("0.1.1-alpha", s.current)
        assertEquals("0.1.2-alpha", s.latest)
        assertTrue(s.canApply)
        assertTrue(s.isVisible)
        assertNull(s.error)
    }

    @Test
    fun stateDrivesButtons() {
        fun st(state: String) = HostUpdateStatus.fromInfo("""{"update":{"current":"1.0.0","latest":null,"state":"$state","error":"boom"}}""")!!
        assertFalse(st("none").isVisible)
        assertTrue(st("available").canApply)
        assertFalse(st("downloading").canApply)
        assertFalse(st("applying").canApply)
        assertEquals("boom", st("error").error)
        assertEquals("", st("none").latest)
    }

    @Test
    fun errorStateAllowsRetryOnlyWhenNewerIsKnown() {
        fun st(current: String, latest: String?) = HostUpdateStatus.fromInfo(
            """{"update":{"current":"$current","latest":${latest?.let { "\"$it\"" } ?: "null"},"state":"error","error":"rolled back"}}""",
        )!!
        assertTrue(st("0.1.1-alpha", "0.1.2").canApply)
        assertFalse(st("0.1.2", "0.1.2").canApply)
        assertFalse(st("0.1.2", "0.1.1").canApply)
        assertFalse(st("0.1.2", null).canApply)
        assertFalse(st("0.1.2", "garbage").canApply)
    }

    @Test
    fun olderHostWithoutUpdateField() {
        assertNull(HostUpdateStatus.fromInfo("""{"name":"PC","version":"0.1.0"}"""))
        assertNull(HostUpdateStatus.fromInfo("garbage"))
    }

    @Test
    fun conflictBodyRunningSessions() {
        assertEquals(2, HostUpdateStatus.runningSessionsFrom409("""{"error":"sessions running","runningSessions":2}"""))
        assertEquals(3, HostUpdateStatus.runningSessionsFrom409("""{"error":"x","runningSessions":["a","b","c"]}"""))
        assertNull(HostUpdateStatus.runningSessionsFrom409("""{"error":"x"}"""))
        assertNull(HostUpdateStatus.runningSessionsFrom409(""))
    }

    @Test
    fun autoCheckOncePerDay() {
        val day = UpdatePolicy.AUTO_CHECK_INTERVAL_MS
        val now = 1_000_000_000_000L
        assertTrue(UpdatePolicy.shouldAutoCheck(0, now))
        assertFalse(UpdatePolicy.shouldAutoCheck(now - day + 1, now))
        assertTrue(UpdatePolicy.shouldAutoCheck(now - day, now))
        assertTrue(UpdatePolicy.shouldAutoCheck(now + 60_000, now)) // clock moved backwards
    }
}
