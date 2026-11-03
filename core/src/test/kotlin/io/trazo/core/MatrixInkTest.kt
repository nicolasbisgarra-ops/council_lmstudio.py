package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Una matriz escrita a mano se lee por filas y su resultado se dibuja como grilla entre corchetes. */
class MatrixInkTest {
    private val lib = PresetPatterns.install(emptyList(), 0)
    private val byId = lib.associateBy { it.id }
    private val ids = IdGenerator.sequential("matriz")

    private fun put(key: String, b: Bounds) = InkReplacement.place(byId.getValue(PresetPatterns.PREFIX + key), b, ids)

    @Test fun `el resultado de una matriz es una grilla`() {
        val ink = put("corchete-abre", Bounds(0f, -6f, 10f, 68f)) +
            put("1", Bounds(16f, 0f, 30f, 20f)) + put("2", Bounds(52f, 0f, 66f, 20f)) +
            put("3", Bounds(16f, 42f, 30f, 62f)) + put("4", Bounds(52f, 42f, 66f, 62f)) +
            put("corchete-cierra", Bounds(74f, -6f, 84f, 68f)) +
            put("igual", Bounds(96f, 26f, 120f, 36f))
        assertEquals("[[1 2];[3 4]]=", MathNotes.reviewLines(ink, lib).single().text)
        val result = MathNotes.results(ink, lib)
        assertTrue("sin resultado", result.isNotEmpty())
        val box = result.bounds()
        // Dos filas apiladas: más alto que un renglón y a la derecha del igual.
        assertTrue("$box", box.height > 40f && box.left > 120f)
        // Corchetes del alto de toda la grilla, uno a cada lado.
        val brackets = result.filter { it.points.size == 4 && listOf(it).bounds().height >= box.height - 0.5f }
        assertEquals(2, brackets.size)
        assertTrue(brackets.minOf { listOf(it).bounds().left } <= box.left + 0.5f && brackets.maxOf { listOf(it).bounds().right } >= box.right - 0.5f)
    }
}
