package io.trazo.core

import io.trazo.core.math.Poly
import io.trazo.core.math.Polynomials
import io.trazo.core.math.Rational
import io.trazo.core.math.Root
import io.trazo.core.math.isReal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ecuaciones, polinomios y sistemas que resuelve «Calcular». */
class PolynomialsTest {
    private fun poly(vararg c: Long) = Poly(c.map { Rational.of(it) })

    @Test fun `ecuaciones de primer grado`() {
        assertEquals("x = 2", MathNotes.solveText(listOf("2x+3=7")))
        assertEquals("x = 3/2", MathNotes.solveText(listOf("4x=6")))
        // Con otra letra que ya tiene valor.
        assertEquals("x = 5", MathNotes.solveText(listOf("x+a=8"), listOf("a=3")))
    }

    @Test fun `ecuaciones de segundo grado exactas y con raiz`() {
        assertEquals("x = 2 ; x = 3", MathNotes.solveText(listOf("x^2-5x+6=0")))
        assertEquals("x = −1", MathNotes.solveText(listOf("x^2+2x+1=0")))
        val s = MathNotes.solveText(listOf("x^2-2=0"))
        assertTrue(s, s.contains("√2") && s.contains(";"))
    }

    @Test fun `sin solucion real ofrece los imaginarios`() {
        val no = MathNotes.solveText(listOf("x^2+1=0"))
        assertTrue(no, no.startsWith("!*") && no.contains("no tiene solución real"))
        val yes = MathNotes.solveText(listOf("x^2+1=0"), complex = true)
        assertTrue(yes, yes.contains("i") && yes.contains(";"))
        // Si tiene alguna real, no pregunta: x³ − x² + x − 1 = (x − 1)(x² + 1).
        assertEquals("x = 1", MathNotes.solveText(listOf("x^3-x^2+x-1=0")))
    }

    @Test fun `grados altos hasta diez`() {
        // (x − 1)(x − 2)(x − 3)(x − 4) = x⁴ − 10x³ + 35x² − 50x + 24
        assertEquals("x = 1 ; x = 2 ; x = 3 ; x = 4", MathNotes.solveText(listOf("x^4-10x^3+35x^2-50x+24=0")))
        val r = Polynomials.roots(Poly(listOf(Rational.of(-1)) + List(9) { Rational.ZERO } + listOf(Rational.ONE)), complex = false)!!
        assertEquals(2, r.size) // x¹⁰ = 1 → ±1
        assertTrue(r.all { it.isReal })
        val all = Polynomials.roots(Poly(listOf(Rational.of(-1)) + List(9) { Rational.ZERO } + listOf(Rational.ONE)), complex = true)!!
        assertEquals(10, all.size)
        assertTrue(MathNotes.solveText(listOf("x^11=1")).startsWith("!"))
    }

    @Test fun `sistemas lineales de dos y tres incognitas`() {
        assertEquals("x = 2 ; y = 1", MathNotes.solveText(listOf("x+y=3", "x-y=1")))
        assertEquals("x = 1 ; y = 2 ; z = 3", MathNotes.solveText(listOf("x+y+z=6", "x-y=-1", "2z-x=5")))
        assertTrue(MathNotes.solveText(listOf("x+y=3", "2x+2y=6")).startsWith("!"))
    }

    @Test fun `factoriza y divide polinomios`() {
        assertEquals("(x − 2)(x − 3)", MathNotes.factorText("x^2-5x+6"))
        val (q, r) = poly(-6, 1, 1).divide(poly(-2, 1))
        assertEquals(poly(3, 1), q); assertTrue(r.isZero)
        val (q2, r2) = poly(1, 0, 1).divide(poly(-1, 1))
        assertEquals(poly(1, 1), q2); assertEquals(poly(2), r2)
        val exact = MathNotes.evaluate(listOf("(x^2+x-6)/(x-2)")).single()
        assertEquals("x + 3", (exact as io.trazo.core.math.LineResult.Value).value.display())
        val rest = MathNotes.evaluate(listOf("(x^2+1)/(x-1)")).single() as io.trazo.core.math.LineResult.Value
        assertTrue(rest.value.display(), rest.value.display().contains("2/(x"))
    }

    @Test fun `raices exactas como raiz cuadrada`() {
        val r = Polynomials.roots(poly(-2, 0, 1), complex = false)!!
        assertTrue(r.all { it is Root.Exact && it.m == java.math.BigInteger.TWO })
    }
}
