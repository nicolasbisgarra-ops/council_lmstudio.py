package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Renglones: los corchetes y compañía ocupan 1, 2 o 3 renglones con las puntas en las líneas. */
class PaperRowsTest {
    private val bracket = PresetPatterns.all().first { it.id == PresetPatterns.PREFIX + "corchete-abre" }

    private fun placed(p: InkPattern, gesture: Bounds, paper: Paper, rows: Int): Bounds {
        val anchor = PaperRows.snap(p, gesture, paper, rows)!!
        return InkReplacement.place(p, anchor, IdGenerator.RANDOM).bounds()
    }

    @Test fun `corchete en 1 2 y 3 renglones de cuadricula`() {
        for (rows in 1..3) {
            val out = placed(bracket, Bounds(100f, 70f, 110f, 120f), Paper.GRID, rows)
            assertEquals(rows * 32f, out.height, 0.6f)
            assertEquals(0f, Math.floorMod(out.top.toInt() + 1, 32).toFloat() - 1f, 1.1f)
        }
    }

    @Test fun `en rayado usa el renglon mas cercano`() {
        // Gesto entre las líneas 184 y 248.
        val out = placed(bracket, Bounds(100f, 190f, 112f, 240f), Paper.LINES, 1)
        assertEquals(184f, out.top, 0.6f)
        assertEquals(248f, out.bottom, 0.6f)
    }

    @Test fun `sin renglones o papel liso no cambia nada`() {
        val g = Bounds(0f, 0f, 10f, 40f)
        assertNull(PaperRows.snap(bracket, g, Paper.GRID, 0))
        assertNull(PaperRows.snap(bracket, g, Paper.PLAIN, 2))
    }

    @Test fun `las letras no salen del renglon salvo que se marque`() {
        val s = InkStroke("a", listOf(InkPoint(0f, 0f), InkPoint(20f, 30f)))
        val letter = InkPattern("user-a", "a", listOf(listOf(s)), listOf(s))
        assertFalse(PaperRows.exceedsRow(letter))
        assertNull(PaperRows.snap(letter, Bounds(0f, 0f, 20f, 30f), Paper.GRID, 2))
        assertTrue(PaperRows.exceedsRow(letter.copy(exceedsRow = true)))
        assertTrue(PaperRows.exceedsRow(bracket))
        assertFalse(PaperRows.exceedsRow(bracket.copy(exceedsRow = false)))
    }
}
