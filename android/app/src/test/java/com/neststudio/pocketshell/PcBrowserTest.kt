package com.neststudio.pocketshell

import com.neststudio.pocketshell.core.Crumbs
import com.neststudio.pocketshell.core.PcPath
import com.neststudio.pocketshell.core.PcPlace
import org.junit.Assert.assertEquals
import org.junit.Test

class PcBrowserTest {
    @Test
    fun parentGoesUpToTheDriveThenToMyPc() {
        assertEquals("C:\\Work", PcPath.parentOrTop("C:\\Work\\app"))
        assertEquals("C:\\", PcPath.parentOrTop("C:\\Work"))
        assertEquals("C:\\", PcPath.parentOrTop("C:\\Work\\"))
        assertEquals("", PcPath.parentOrTop("C:\\"))
        assertEquals("", PcPath.parentOrTop("C:"))
    }

    @Test
    fun nameIsTheLastFolderOrTheDrive() {
        assertEquals("app", PcPath.name("C:\\Work\\app\\"))
        assertEquals("C:", PcPath.name("C:\\"))
    }

    @Test
    fun crumbsStartAtMyPc() {
        assertEquals(listOf("내 PC" to ""), Crumbs.ofPc("", "내 PC"))
        assertEquals(
            listOf("내 PC" to "", "C:" to "C:\\", "Work" to "C:\\Work", "app" to "C:\\Work\\app"),
            Crumbs.ofPc("C:\\Work\\app", "내 PC"),
        )
        assertEquals(listOf("내 PC" to "", "D:" to "D:\\"), Crumbs.ofPc("D:\\", "내 PC"))
    }

    @Test
    fun placesParse() {
        val text = """{"places":[
            {"name":"사용자 폴더","path":"C:\\Users\\me","kind":"folder"},
            {"name":"C: Windows","path":"C:\\","kind":"drive"},
            {"name":"broken","path":"","kind":"folder"}
        ]}"""
        assertEquals(
            listOf(PcPlace("사용자 폴더", "C:\\Users\\me", false), PcPlace("C: Windows", "C:\\", true)),
            PcPlace.parseList(text),
        )
    }
}
