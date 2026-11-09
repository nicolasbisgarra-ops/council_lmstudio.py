package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfImportTest {
    private fun asset(i: Int) = "%064x.webp".format(i)

    @Test fun `an A4 page fills the sheet and a landscape one is centered`() {
        val a4 = PdfImport.fit(1600, 2263)
        assertEquals(0f, a4.left, 0.5f); assertEquals(1000f, a4.right, 0.5f)
        assertEquals(1414f, a4.bottom - a4.top, 1f)
        val wide = PdfImport.fit(2000, 1000)
        assertEquals(1000f, wide.right - wide.left, 0.01f)
        assertEquals(500f, wide.bottom - wide.top, 0.01f)
        assertEquals(1414f / 2, (wide.top + wide.bottom) / 2, 0.01f)
    }

    @Test fun `one plain sheet per pdf page with a locked background`() {
        val nb = PdfImport.notebook("Guía", (1..3).map { PdfImport.RenderedPage(asset(it), 1600, 2263) }, IdGenerator.sequential("p"), now = 5L)
        assertEquals(3, nb.pages.size)
        assertEquals(Paper.PLAIN, nb.paper)
        assertEquals(listOf(asset(1), asset(2), asset(3)), nb.pages.map { it.images.single().asset })
        assertTrue(nb.pages.all { it.images.single().locked && it.strokes.isEmpty() })
        assertEquals(nb.pages.flatMap { p -> p.images.map { it.id } + p.id }.size + 1, (nb.pages.flatMap { p -> p.images.map { it.id } + p.id } + nb.id).toSet().size)
    }

    @Test fun `too many pages are capped`() {
        val nb = PdfImport.notebook("x", (1..PdfImport.MAX_PAGES + 5).map { PdfImport.RenderedPage(asset(it), 100, 141) })
        assertEquals(PdfImport.MAX_PAGES, nb.pages.size)
    }

    @Test fun `name comes from the file name`() {
        assertEquals("Apunte parcial 2", PdfImport.nameFor("docs/Apunte_parcial_2.pdf"))
        assertEquals("PDF", PdfImport.nameFor(null))
    }

    @Test fun `locked background cannot be selected moved or cleared`() {
        val bg = PdfImport.page(PdfImport.RenderedPage(asset(1), 1600, 2263), IdGenerator.sequential("b"))
        val c = InkController(PageState(initialImages = bg.images))
        c.selectAll()
        assertTrue(c.selection.isEmpty())
        assertEquals(null, c.imageAt(500f, 700f))
        c.clearPage()
        assertFalse(c.page.images.isEmpty())
    }
}
