package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandInTest {
    private val stroke = InkStroke("s", listOf(InkPoint(1f, 1f), InkPoint(5f, 5f)))

    @Test fun `skips blank pages but keeps pages with only results`() {
        val pages = listOf(Page("a"), Page("b", strokes = listOf(stroke)), Page("c"), Page("d"))
        assertEquals(listOf(1, 3), HandIn.pagesToInclude(pages) { if (it.id == "d") listOf(stroke) else emptyList() })
        assertTrue(HandIn.isBlank(Page("x")))
        assertFalse(HandIn.isBlank(Page("x"), listOf(stroke)))
    }

    @Test fun `an all-blank notebook still exports its first page`() {
        assertEquals(listOf(0), HandIn.pagesToInclude(listOf(Page("a"), Page("b"))))
    }

    @Test fun `footer shows name and page count`() {
        assertEquals("Análisis · 2 / 5", HandIn.footer(" Análisis ", 2, 5))
        assertEquals("Análisis", HandIn.footer("Análisis", 1, 1))
        assertEquals("3 / 4", HandIn.footer("  ", 3, 4))
    }
}
