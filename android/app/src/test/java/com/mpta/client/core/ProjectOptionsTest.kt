package com.mpta.client.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectOptionsTest {
    @Test
    fun parsesRootsAndAgents() {
        val o = ProjectOptions.parse(
            """{"roots":["E:\\AIWork\\RNDAIProjects",""],"agents":[{"id":"claude","name":"Claude"},{"id":"codex","name":"GPT (Codex)"},{"id":""}]}""",
        )
        assertEquals(listOf("E:\\AIWork\\RNDAIProjects"), o.roots)
        assertEquals(listOf(ProjectAgent("claude", "Claude"), ProjectAgent("codex", "GPT (Codex)")), o.agents)
    }

    @Test
    fun missingFieldsAreEmpty() {
        val o = ProjectOptions.parse("{}")
        assertEquals(emptyList<String>(), o.roots)
        assertEquals(emptyList<ProjectAgent>(), o.agents)
    }

    @Test
    fun serverErrorTextReadsErrorField() {
        assertEquals("이름에 쓸 수 없는 문자가 있습니다", serverErrorText("""{"error":"이름에 쓸 수 없는 문자가 있습니다"}"""))
        assertNull(serverErrorText("not json"))
        assertNull(serverErrorText("{}"))
    }
}
