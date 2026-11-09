package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSearchTest {
    private val lib = PresetPatterns.install(emptyList(), 0)
    private val byId = lib.associateBy { it.id }
    private val ids = IdGenerator.sequential("buscar")

    /** Un renglón de signos de la biblioteca a la altura [y]. */
    private fun rowOf(y: Float, vararg keys: String): List<InkStroke> {
        var x = 0f
        return keys.flatMap { key ->
            val p = byId.getValue(PresetPatterns.PREFIX + key)
            val anchor = when (key) {
                "mas", "igual" -> Bounds(x, y + 15f, x + 30f, y + 25f)
                "coma" -> Bounds(x, y + 34f, x + 8f, y + 50f)
                else -> Bounds(x, y, x + 25f, y + 40f)
            }
            InkReplacement.place(p, anchor, ids).also { x = it.bounds().right + 12f }
        }
    }

    private val page = Page(
        "p1",
        rowOf(0f, "2", "letra-h", "mas", "3", "igual") + rowOf(120f, "1", "coma", "5", "mas", "1", "igual"),
    )
    private val entries = NoteSearch.index("nb", "Física", 3, page, lib)

    @Test fun `every legible row of the page is indexed with its page`() {
        assertEquals(listOf("2h+3=", "1.5+1="), entries.map { it.text })
        assertTrue(entries.all { it.pageIndex == 3 && it.notebookName == "Física" })
        assertTrue(entries[0].bounds.bottom < entries[1].bounds.top)
    }

    @Test fun `search ignores case spaces and decimal comma`() {
        assertEquals("2h+3=", NoteSearch.search(entries, "2H + 3").single().text)
        assertEquals("1.5+1=", NoteSearch.search(entries, "1,5").single().text)
        assertEquals(2, NoteSearch.search(entries, "+").size)
        assertTrue(NoteSearch.search(entries, "   ").isEmpty())
        assertTrue(NoteSearch.search(entries, "7").isEmpty())
    }

    @Test fun `fold removes accents and unifies signs`() {
        assertEquals("fisica2*3-1", NoteSearch.fold("Física 2×3 − 1"))
        assertEquals("a*b", NoteSearch.fold("a · b"))
    }

    @Test fun `empty or unreadable pages give nothing`() {
        assertTrue(NoteSearch.index("nb", "x", 0, Page("vacía"), lib).isEmpty())
        val scribble = InkStroke("s", (0..20).map { InkPoint(it * 5f, if (it % 2 == 0) 0f else 30f, 0.5f, 0L) })
        assertTrue(NoteSearch.index("nb", "x", 0, Page("garabato", listOf(scribble)), lib).isEmpty())
    }
}
