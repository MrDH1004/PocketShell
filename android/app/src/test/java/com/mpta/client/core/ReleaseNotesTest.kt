package com.mpta.client.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun dropsFullChangelogLine() {
        assertEquals("", ReleaseNotes.clean("**Full Changelog**: https://github.com/a/b/compare/v0.2.9...v0.2.10"))
    }

    @Test
    fun keepsChangesAsBullets() {
        val body = "## What's Changed\n* 입력 중복 수정 by @me in https://github.com/a/b/pull/3\n- **새 프로젝트** 메뉴\n\n\n**Full Changelog**: https://x/compare/v1...v2"
        assertEquals("• 입력 중복 수정\n• 새 프로젝트 메뉴", ReleaseNotes.clean(body))
    }
}
