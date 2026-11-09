package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La explicación de un resultado escrita a mano en la hoja, con la letra del usuario. */
class HandwriteTest {
    private val patterns = PresetPatterns.all()
    private val signs = PresetPatterns.signs().associateBy { it.name }
    private val user = PresetPatterns.bundled().associateBy { it.name }

    private fun row(y: Float, vararg names: String): List<InkStroke> {
        val ids = IdGenerator.sequential("t$y")
        var x = 0f
        return names.flatMap { name ->
            val p = signs[name] ?: user.getValue(name)
            val flat = p.sizing == OutputSizing.MATCH_WIDTH
            val anchor = if (flat) Bounds(x, y + 15f, x + 30f, y + 25f) else Bounds(x, y, x + 25f, y + 40f)
            InkReplacement.place(p, anchor, ids).also { x = it.bounds().right + 12f }
        }
    }

    private val page = row(0f, "2", "Más", "3", "Igual")
    private val explanation = MathNotes.Explanation(page.bounds(), listOf("2 + 3 = 5", "la suma de dos números"))

    private fun write(p: List<InkPattern> = patterns, e: MathNotes.Explanation = explanation, ink: List<InkStroke> = page) =
        MathNotes.handwrite(ink, p, e, IdGenerator.sequential("h"))

    @Test fun `la explicacion se escribe debajo de la cuenta`() {
        val out = write()
        assertTrue(out.isNotEmpty())
        assertTrue(out.bounds().top > explanation.bounds.bottom)
        assertTrue(out.bounds().left >= explanation.bounds.left - 10f)
        // Un grupo por renglón.
        assertEquals(2, out.map { it.groupId }.distinct().size)
    }

    @Test fun `ningun trazo queda como patron ni como cuenta`() {
        assertTrue(write().all { it.patternId == null && it.calc == null })
    }

    @Test fun `toma el color de la cuenta o el pedido`() {
        val color = page.first().color
        assertTrue(write().all { it.color == color })
        assertTrue(MathNotes.handwrite(page, patterns, explanation, IdGenerator.sequential("h"), color = 0xFF00AA00.toInt())
            .all { it.color == 0xFF00AA00.toInt() })
    }

    @Test fun `mide la inclinacion de la letra a mano`() {
        val slanted = listOf(InkStroke("s", listOf(InkPoint(0f, 40f), InkPoint(6f, 20f), InkPoint(12f, 0f))))
        assertEquals(0.3f, HandFont.slant(slanted, emptyMap()), 0.01f)
        val upright = listOf(InkStroke("u", listOf(InkPoint(0f, 40f), InkPoint(0f, 0f))))
        assertEquals(0f, HandFont.slant(upright, emptyMap()), 0.01f)
        assertTrue(HandFont.slant(page, patterns.associateBy { it.id }) in -0.4f..0.4f)
    }

    @Test fun `usa las cifras que dibujo el usuario`() {
        val ink = listOf(InkStroke("a", listOf(InkPoint(0f, 0f), InkPoint(0f, 50f), InkPoint(30f, 100f))))
        val five = InkPattern("mi-5", "5", listOf(ink), ink)
        val e = MathNotes.Explanation(page.bounds(), listOf("5"))
        val plain = write(e = e)
        val mine = write(patterns + five, e)
        assertNotEquals(plain.map { it.points }, mine.map { it.points })
        assertEquals(1, mine.size)
        assertEquals(null, mine.single().patternId)
    }

    @Test fun `las tildes suman trazos`() {
        val a = write(e = MathNotes.Explanation(page.bounds(), listOf("a")))
        val accented = write(e = MathNotes.Explanation(page.bounds(), listOf("á")))
        assertTrue(accented.size > a.size)
    }

    @Test fun `un texto largo se parte en renglones`() {
        val long = MathNotes.Explanation(page.bounds(), listOf(List(30) { "palabra" }.joinToString(" ")))
        val out = write(e = long)
        assertTrue(out.map { it.groupId }.distinct().size > 1)
        val h = out.groupBy { it.groupId }.values.maxOf { it.bounds().width }
        assertTrue(h <= maxOf(long.bounds.width, 80f * 18f) + 1f)
    }

    @Test fun `el mismo texto se escribe igual`() {
        assertEquals(write(), write())
    }

    @Test fun `todos los caracteres de la letra tienen trazos dentro de su caja`() {
        for ((c, g) in HandFont.glyphs) {
            if (c == ' ') continue
            assertTrue("$c", g.strokes.isNotEmpty() && g.strokes.all { it.isNotEmpty() })
            for ((x, y) in g.strokes.flatten()) {
                assertTrue("$c x=$x", x >= -1f && x <= g.w + 1f)
                assertTrue("$c y=$y", y >= -13f && y <= 5f)
            }
        }
    }
}
