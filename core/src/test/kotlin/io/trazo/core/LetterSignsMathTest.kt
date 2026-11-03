package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Letras latinas (a–z, A–Z) y coma de la biblioteca: se leen como variables y como coma decimal o separador. */
class LetterSignsMathTest {
    private val lib = PresetPatterns.install(emptyList(), 0)
    private val byId = lib.associateBy { it.id }
    private val ids = IdGenerator.sequential("letras")

    private fun id(key: String) = PresetPatterns.PREFIX + key

    /** Escribe los signos de izquierda a derecha: alto de letra, raya chata o coma colgando de la línea. */
    private fun rowOf(vararg keys: String): List<InkStroke> {
        var x = 0f
        return keys.flatMap { key ->
            val p = byId.getValue(id(key))
            val anchor = when (key) {
                "mas", "igual" -> Bounds(x, 15f, x + 30f, 25f)
                "coma" -> Bounds(x, 34f, x + 8f, 50f)
                else -> Bounds(x, 0f, x + 25f, 40f)
            }
            InkReplacement.place(p, anchor, ids).also { x = it.bounds().right + 12f }
        }
    }

    @Test fun `every latin letter in both cases and the comma are in the library`() {
        val names = PresetPatterns.signs().filter { it.category == PatternCategory.LETTERS }.map { it.name }.toSet()
        for (c in ('a'..'z') + ('A'..'Z')) assertTrue("falta $c", c.toString() in names)
        assertEquals("Coma", MathNotes.patternSymbol(byId.getValue(id("coma"))))
        assertEquals("A", MathNotes.patternSymbol(byId.getValue(id("letra-mayus-a"))))
        assertEquals("h", MathNotes.patternSymbol(byId.getValue(id("letra-h"))))
        // Las mayúsculas no ceden ante su minúscula solo por llamarse igual.
        assertTrue(byId.getValue(id("letra-mayus-a")).enabled)
        assertTrue(byId.getValue(id("letra-h")).enabled && byId.getValue(id("letra-mayus-h")).enabled)
    }

    @Test fun `upper and lower case letters are different variables`() {
        val ink = rowOf("2", "letra-mayus-h", "mas", "3", "letra-mayus-h", "mas", "letra-h", "igual")
        assertEquals("2H+3H+h=", MathNotes.reviewLines(ink, lib).single().text)
        val result = MathNotes.results(ink, lib).mapNotNull { it.patternId }.toSet()
        assertTrue("$result", id("5") in result && id("letra-mayus-h") in result && id("letra-h") in result)
    }

    @Test fun `the comma is a decimal comma between digits`() {
        val ink = rowOf("1", "coma", "5", "mas", "1", "igual")
        assertEquals("1.5+1=", MathNotes.reviewLines(ink, lib).single().text)
        val result = MathNotes.results(ink, lib).mapNotNull { it.patternId }.toSet()
        // Escrita con decimales, responde con decimales: 2,5 (con la coma de la biblioteca), no 5/2.
        assertTrue("$result", id("5") in result && id("coma") in result && id("barra") !in result)
        // Sin decimales escritos queda la fracción exacta; un decimal que no termina, también.
        assertTrue(MathNotes.asWritten("5/2=", io.trazo.core.math.ExactValue.of(io.trazo.core.math.Rational.of(5, 2))) is io.trazo.core.math.ExactValue)
        assertTrue(MathNotes.asWritten("0,5/1,5=", io.trazo.core.math.ExactValue.of(io.trazo.core.math.Rational.of(1, 3))) is io.trazo.core.math.ExactValue)
    }
}
