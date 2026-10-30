package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Abrir un cuaderno largo solo decodifica la página que se mira; el archivo sigue siendo el mismo. */
class LazyNotebookTest {
    private val ids = IdGenerator.sequential("l")

    private fun stroke(x: Float, note: String = "") =
        InkStroke(ids.next() + note, listOf(InkPoint(x, 10f), InkPoint(x + 5f, 20f)))

    private fun notebook(pages: Int) = Notebook(
        "nb", "Cuaderno \"raro\" {con} [signos]",
        List(pages) { i -> Page("p$i", listOf(stroke(i.toFloat(), "\\\"}]"), stroke(i + 50f))) },
        Paper.GRID, createdAt = 5L, updatedAt = 9L, lastPage = 2,
    )

    @Test fun `el archivo perezoso es el mismo cuaderno`() {
        val n = notebook(4)
        val lazy = TrazoFormat.decodeLazy(TrazoFormat.encodeNotebook(n))
        assertEquals(4, lazy.pageCount)
        assertTrue(lazy.slots.none { it.isLoaded })
        assertEquals(listOf("p0", "p1", "p2", "p3"), lazy.slots.map { it.id })
        assertEquals(2, lazy.openPage)
        assertEquals(n, lazy.toNotebook())
        // Guardarlo sin abrir páginas da un archivo que se lee igual por los dos caminos.
        val again = TrazoFormat.encodeLazy(lazy)
        assertEquals(n, TrazoFormat.decodeNotebook(again))
        assertEquals(n, TrazoFormat.decodeLazy(again).toNotebook())
    }

    @Test fun `editar una pagina solo carga esa`() {
        val lazy = TrazoFormat.decodeLazy(TrazoFormat.encodeNotebook(notebook(3))).loadedOnly(1)
        assertEquals(listOf(false, true, false), lazy.slots.map { it.isLoaded })
        val edited = lazy.withPage(1, lazy.page(1).copy(strokes = emptyList()))
        val moved = edited.withPageAdded(Page("nueva")).withPageMoved(3, 0).withPageDuplicated(2).withPageRemoved(1)
        val back = TrazoFormat.decodeNotebook(TrazoFormat.encodeLazy(moved.loadedOnly(0)))
        // [p0 p1 p2] + nueva → nueva al principio → p1 duplicada → sin p0.
        assertEquals(listOf("nueva", "p1"), back.pages.take(2).map { it.id })
        assertEquals("p2", back.pages.last().id)
        assertEquals(4, back.pages.size)
        assertTrue(back.pages[1].strokes.isEmpty() && back.pages[2].strokes.isEmpty())
        assertFalse(moved.loadedOnly(0).slots.drop(1).any { it.isLoaded })
    }

    @Test fun `un archivo viejo sin ultima pagina abre en la primera`() {
        val old = TrazoFormat.encodeNotebook(notebook(2)).replace(",\"lastPage\":2", "")
        assertEquals(0, TrazoFormat.decodeLazy(old).openPage)
        assertEquals(1, TrazoFormat.decodeLazy(TrazoFormat.encodeNotebook(notebook(2).copy(lastPage = 7))).openPage)
    }
}
