package com.mpta.client

import com.mpta.client.core.FileListing
import com.mpta.client.core.FilePreview
import com.mpta.client.core.PcPath
import com.mpta.client.core.PreviewKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesTest {

    @Test
    fun parsesListing() {
        val l = FileListing.parse(
            """{"path":"E:\\proj","entries":[{"name":"src","dir":true,"size":0,"modified":"2026-11-08T00:48:12.1190000Z"},
            {"name":"a.txt","dir":false,"size":12,"modified":"2026-11-08T00:48:12Z"}]}"""
        )
        assertEquals("E:\\proj", l.path)
        assertEquals(listOf("src", "a.txt"), l.entries.map { it.name })
        assertTrue(l.entries[0].dir)
        assertEquals(12L, l.entries[1].size)
    }

    @Test
    fun pathsStayInsideTheProjectFolder() {
        val root = "E:\\proj"
        assertEquals("E:\\proj\\src", PcPath.child(root, "src"))
        assertEquals("E:\\a.txt", PcPath.child("E:\\", "a.txt"))
        assertNull(PcPath.parentWithin(root, root))
        assertNull(PcPath.parentWithin("E:\\proj\\", root))
        assertEquals(root, PcPath.parentWithin("E:\\proj\\src", root))
        assertEquals("E:\\PROJ\\src", PcPath.parentWithin("E:\\PROJ\\src\\main", root)) // case of the path kept
        assertEquals("src\\main", PcPath.relative("E:\\proj\\src\\main", root))
        assertEquals("", PcPath.relative("E:\\proj", root))
        assertTrue(PcPath.inside("e:\\proj\\x", root))
        assertFalse(PcPath.inside("E:\\project2", root))
        assertEquals("E:\\", PcPath.parentWithin("E:\\proj", "E:\\"))
    }

    @Test
    fun previewKinds() {
        assertEquals(PreviewKind.TEXT, FilePreview.kind("Program.cs", 100))
        assertEquals(PreviewKind.TEXT, FilePreview.kind("Makefile", 100))
        assertEquals(PreviewKind.IMAGE, FilePreview.kind("shot.PNG", 5_000_000))
        assertEquals(PreviewKind.NONE, FilePreview.kind("app.exe", 100))
        assertEquals(PreviewKind.NONE, FilePreview.kind("huge.log", FilePreview.MAX_TEXT_BYTES + 1))
        assertTrue(FilePreview.looksBinary(byteArrayOf(65, 0, 66)))
        assertFalse(FilePreview.looksBinary("abc".toByteArray()))
    }

    @Test
    fun decodesUtf8AndCp949() {
        assertEquals("한글", FilePreview.decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "한글".toByteArray()))
        assertEquals("한글", FilePreview.decodeText("한글".toByteArray(charset("x-windows-949"))))
        assertEquals("1.5 KB", FilePreview.humanSize(1536))
    }
}
