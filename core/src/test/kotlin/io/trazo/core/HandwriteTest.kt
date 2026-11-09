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

    private fun mine(id: String, name: String, vararg pts: Pair<Float, Float>): InkPattern {
        val ink = listOf(InkStroke(id, pts.map { (x, y) -> InkPoint(x, y) }))
        return InkPattern(id, name, listOf(ink), ink)
    }

    private fun text(s: String, p: List<InkPattern> = patterns) = write(p, MathNotes.Explanation(page.bounds(), listOf(s)))

    @Test fun `usa la mayuscula que dibujo el usuario`() {
        val a = mine("mi-A", "A", 0f to 100f, 25f to 0f, 50f to 100f)
        assertNotEquals(text("A").map { it.points }, text("A", patterns + a).map { it.points })
        assertEquals(1, text("A", patterns + a).size)
    }

    @Test fun `dos letras a del usuario se van turnando`() {
        val a1 = mine("mi-a1", "a", 0f to 0f, 0f to 50f, 40f to 50f)
        val a2 = mine("mi-a2", "a", 0f to 0f, 40f to 25f, 0f to 50f, 40f to 50f)
        // Puede haber otra «a» de fábrica en la lista: con tres letras salen las dos del usuario.
        val counts = text("aaa", patterns + a1 + a2).map { it.points.size }
        assertTrue("$counts", counts.containsAll(listOf(3, 4)))
    }

    @Test fun `los simbolos nuevos tienen trazos`() {
        for (c in "∫∂∇∈∪∩⊂∅∀∃∴∠⊥∥≡∝←↔⇔↑↓ρηκνξψχζΩΠΦΘΛΓ~&@\$€§'") {
            assertTrue("$c", c in HandFont.glyphs)
            assertTrue("$c", text(c.toString()).isNotEmpty())
        }
    }

    @Test fun `los alias se escriben como su caracter`() {
        // El temblor depende del texto, así que se compara la forma: misma cantidad de trazos y puntos.
        fun shape(s: String) = text(s).map { it.points.size }
        assertEquals(shape("Σ"), shape("∑"))
        assertEquals(shape("Ω"), shape("\u2126"))
        assertEquals(shape("'"), shape("’"))
    }

    @Test fun `el renglon no se va para arriba ni para abajo`() {
        val out = text(List(40) { "o" }.joinToString(""))
        val tops = out.groupBy { it.groupId }.values.first().sortedBy { listOf(it).bounds().left }.map { listOf(it).bounds().centerY }
        assertTrue(tops.max() - tops.min() < 40f)
    }
}
