package com.neststudio.pocketshell

import com.neststudio.pocketshell.core.Crumbs
import com.neststudio.pocketshell.core.FileCategory
import com.neststudio.pocketshell.core.FileEntry
import com.neststudio.pocketshell.core.FileKinds
import com.neststudio.pocketshell.core.FileSort
import com.neststudio.pocketshell.core.RelativeTime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FileBrowserLogicTest {

    private fun f(name: String, size: Long = 0, modified: String = "2026-11-08T00:00:00Z") = FileEntry(name, false, size, modified)
    private fun d(name: String, modified: String = "2026-11-08T00:00:00Z") = FileEntry(name, true, 0, modified)

    @Test
    fun categories() {
        assertEquals(FileCategory.FOLDER, FileKinds.category(d("src")))
        assertEquals(FileCategory.CODE, FileKinds.category(f("Main.KT")))
        assertEquals(FileCategory.IMAGE, FileKinds.category(f("a.png")))
        assertEquals(FileCategory.TEXT, FileKinds.category(f("README.md")))
        assertEquals(FileCategory.TEXT, FileKinds.category(f(".gitignore")))
        assertEquals(FileCategory.ARCHIVE, FileKinds.category(f("x.zip")))
        assertEquals(FileCategory.OTHER, FileKinds.category(f("blob")))
    }

    @Test
    fun sortKeepsFoldersFirst() {
        val list = listOf(f("b.txt", 5, "2026-11-08T03:00:00Z"), d("Zed", "2026-11-08T01:00:00Z"), f("A.txt", 9, "2026-11-08T01:00:00Z"), d("app", "2026-11-08T02:00:00Z"))
        assertEquals(listOf("app", "Zed", "A.txt", "b.txt"), FileSort.NAME.apply(list).map { it.name })
        assertEquals(listOf("app", "Zed", "b.txt", "A.txt"), FileSort.MODIFIED.apply(list).map { it.name })
        assertEquals(listOf("app", "Zed", "A.txt", "b.txt"), FileSort.SIZE.apply(list).map { it.name })
        assertEquals(FileSort.NAME, FileSort.of("bogus"))
    }

    @Test
    fun crumbsFromRoot() {
        assertEquals(listOf("proj" to "E:\\w\\proj"), Crumbs.of("E:\\w\\proj", "E:\\w\\proj"))
        assertEquals(
            listOf("proj" to "E:\\w\\proj", "src" to "E:\\w\\proj\\src", "ui" to "E:\\w\\proj\\src\\ui"),
            Crumbs.of("E:\\w\\proj\\src\\ui", "E:\\w\\proj"),
        )
    }

    @Test
    fun relativeTime() {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        val z = ZoneId.of("Asia/Seoul")
        assertEquals("방금", RelativeTime.format(Instant.parse("2026-11-08T11:59:30Z"), now, z))
        assertEquals("5분 전", RelativeTime.format(Instant.parse("2026-11-08T11:55:00Z"), now, z))
        assertEquals("3시간 전", RelativeTime.format(Instant.parse("2026-11-08T09:00:00Z"), now, z))
        assertEquals("2일 전", RelativeTime.format(Instant.parse("2026-11-06T11:00:00Z"), now, z))
        assertEquals("2026-10-01", RelativeTime.format(Instant.parse("2026-10-01T03:00:00Z"), now, z))
    }
}
