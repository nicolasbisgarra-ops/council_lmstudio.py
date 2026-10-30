package io.trazo.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FoldersAndCoversTest {
    private val legacyV1 = """
        {"format":"trazo-notebook","schemaVersion":1,"notebook":{"id":"n1","name":"Viejo","paper":"GRID",
        "createdAt":5,"updatedAt":9,"lastPage":1,"pages":[{"id":"p1","strokes":[]},{"id":"p2"}]}}
    """.trimIndent()

    private val webp = "RIFF".toByteArray() + byteArrayOf(4, 0, 0, 0) + "WEBPVP8 ".toByteArray()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2)
    private val coverName = "a".repeat(64) + ".webp"

    @Test fun `legacy v1 notebook gets default cover and no folder, eager and lazy`() {
        val eager = TrazoFormat.decodeNotebook(legacyV1)
        assertEquals(CoverKind.FIRST_PAGE, eager.coverKind)
        assertNull(eager.coverImage)
        assertNull(eager.folderId)
        val lazy = TrazoFormat.decodeLazy(legacyV1)
        assertEquals(CoverKind.FIRST_PAGE, lazy.coverKind)
        assertNull(lazy.coverImage)
        assertNull(lazy.folderId)
        assertTrue(lazy.slots.none { it.isLoaded })
        assertEquals(eager, lazy.toNotebook())
    }

    @Test fun `cover and folder survive both serialization paths`() {
        val n = Notebook("n", "Con tapa", listOf(Page("p")), coverKind = CoverKind.IMAGE, coverImage = coverName, folderId = "f1")
        assertEquals(n, TrazoFormat.decodeNotebook(TrazoFormat.encodeNotebook(n)))
        val lazy = TrazoFormat.decodeLazy(TrazoFormat.encodeLazy(LazyNotebook.of(n)))
        assertEquals(n, lazy.toNotebook())
        assertEquals(n, TrazoFormat.decodeNotebook(TrazoFormat.encodeLazy(LazyNotebook.of(n))))
    }

    @Test fun `unknown cover kind from a future version falls back to first page`() {
        val text = legacyV1.replace("\"lastPage\":1", "\"lastPage\":1,\"coverKind\":\"VIDEO\"")
        assertEquals(CoverKind.FIRST_PAGE, TrazoFormat.decodeNotebook(text).coverKind)
        assertEquals(CoverKind.FIRST_PAGE, TrazoFormat.decodeLazy(text).coverKind)
    }

    @Test fun `catalog rejects duplicate ids and orders by key then id`() {
        assertThrows(IllegalArgumentException::class.java) { FolderCatalog(listOf(Folder("a", "A"), Folder("a", "B"))) }
        val c = FolderCatalog(listOf(Folder("z", "Z", orderKey = 1), Folder("b", "B", orderKey = 0), Folder("a", "A", orderKey = 1)))
        assertEquals(listOf("b", "a", "z"), c.ordered().map { it.id })
    }

    @Test fun `add rename recolor`() {
        val ids = IdGenerator.sequential("f")
        val (c1, f1) = FolderCatalog().add("  Álgebra  ", ids = ids)
        val (c2, f2) = c1.add("", ids = ids)
        assertEquals("Álgebra", f1.name)
        assertEquals("Carpeta", f2.name)
        assertTrue(f2.orderKey > f1.orderKey)
        val c3 = c2.renamed(f1.id, "Cálculo").recolored(f2.id, 0xFFFF0000L)
        assertEquals("Cálculo", c3[f1.id]!!.name)
        assertEquals(0xFFFF0000L, c3[f2.id]!!.colorArgb)
        assertThrows(IllegalArgumentException::class.java) { c3.renamed("nope", "x") }
    }

    @Test fun `folder with notebooks cannot be removed until emptied`() {
        val c = FolderCatalog(listOf(Folder("f", "F"), Folder("g", "G")))
        val e = assertThrows(FolderNotEmptyException::class.java) { c.removed("f", listOf("f", null, "f", "g")) }
        assertEquals(2, e.count)
        assertEquals(listOf("g"), c.removed("f", listOf(null, "g")).folders.map { it.id })
    }

    @Test fun `catalog round trip and corrupt file recovery`() {
        val c = FolderCatalog(listOf(Folder("f", "F", 0xFF00FF00L, 3)))
        assertEquals(c, FolderCatalog.decode(FolderCatalog.encode(c)))
        assertEquals(FolderCatalog(), FolderCatalog.decode("{roto"))
        assertEquals(FolderCatalog(), FolderCatalog.decode(null))
        assertEquals(FolderCatalog(), FolderCatalog.decode("""{"format":"otro","schemaVersion":1,"folders":[]}"""))
        val dup = """{"format":"trazo-folders","schemaVersion":1,"folders":[{"id":"a","name":"1"},{"id":"a","name":"2"}]}"""
        assertEquals(listOf("1"), FolderCatalog.decode(dup).folders.map { it.name })
    }

    @Test fun `manual order puts unknown first by recency and keeps the rest stable`() {
        data class N(val id: String, val t: Long)
        val items = listOf(N("a", 1), N("b", 2), N("c", 3), N("new1", 10), N("new2", 20))
        val sorted = NotebookOrder.sorted(items, listOf("c", "gone", "a", "b"), { it.id }, { it.t })
        assertEquals(listOf("new2", "new1", "c", "a", "b"), sorted.map { it.id })
        assertEquals(listOf("c", "a", "b"), NotebookOrder.decode(NotebookOrder.encode(listOf("c", "a", "b", "a"))))
        assertEquals(emptyList<String>(), NotebookOrder.decode("[roto"))
    }

    @Test fun `moving before a target or to the end`() {
        val l = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "d", "b", "c"), NotebookOrder.moved(l, "d", "b"))
        assertEquals(listOf("b", "c", "d", "a"), NotebookOrder.moved(l, "a", null))
        assertEquals(l, NotebookOrder.moved(l, "a", "a"))
        assertEquals(l, NotebookOrder.moved(l, "x", "a"))
        assertEquals(l, NotebookOrder.moved(l, "a", "x"))
    }

    @Test fun `cover names and magic bytes are validated`() {
        assertTrue(Covers.isValidName(coverName))
        assertFalse(Covers.isValidName("../x.webp"))
        assertFalse(Covers.isValidName("A".repeat(64) + ".webp"))
        assertEquals(CoverKind.FIRST_PAGE to null, Covers.normalized(CoverKind.IMAGE, "x.jpg"))
        assertEquals(CoverKind.FIRST_PAGE to null, Covers.normalized(CoverKind.FIRST_PAGE, coverName))
        assertEquals("webp", Covers.extensionOf(webp))
        assertEquals("png", Covers.extensionOf(png))
        assertNull(Covers.extensionOf("GIF89a....".toByteArray()))
        assertNull(Covers.extensionOf(webp + ByteArray(Covers.MAX_BYTES)))
    }

    @Test fun `archive carries the cover and import clears the folder`() {
        val n = Notebook("n", "T", listOf(Page("p")), coverKind = CoverKind.IMAGE, coverImage = coverName, folderId = "f")
        val bytes = ByteArrayOutputStream().also { TrazoFormat.exportArchive(n, emptyList(), it, webp) }.toByteArray()
        val back = TrazoFormat.importArchive(ByteArrayInputStream(bytes))
        assertEquals(n.copy(folderId = null), back.notebook)
        assertArrayEquals(webp, back.cover)
    }

    @Test fun `archive without a valid cover falls back to first page`() {
        val n = Notebook("n", "T", listOf(Page("p")), coverKind = CoverKind.IMAGE, coverImage = coverName)
        for (cover in listOf(null, "nope".toByteArray(), webp + ByteArray(Covers.MAX_BYTES))) {
            val bytes = ByteArrayOutputStream().also { TrazoFormat.exportArchive(n, emptyList(), it, cover) }.toByteArray()
            val back = TrazoFormat.importArchive(ByteArrayInputStream(bytes))
            assertEquals(CoverKind.FIRST_PAGE, back.notebook.coverKind)
            assertNull(back.notebook.coverImage)
            assertNull(back.cover)
        }
    }
}
