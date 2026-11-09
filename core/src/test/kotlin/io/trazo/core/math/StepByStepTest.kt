package io.trazo.core.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepByStepTest {
    private fun List<String>?.has(vararg parts: String) =
        assertTrue(toString(), this != null && parts.all { p -> any { p in it } })

    @Test fun `fractions get a common denominator`() {
        val s = StepByStep.expression("1/2+1/3")!!
        assertEquals("1/2 + 1/3 = 3/6 + 2/6   (denominador común: mcm(2, 3) = 6)", s[0])
        assertEquals("= 5/6   (mismo denominador: se suman los numeradores)", s[1])
    }

    @Test fun `dividing is multiplying by the inverse and results are simplified`() {
        StepByStep.expression("(1/2)/(3/4)").has("1/2·4/3", "inversa", "= 2/3")
        StepByStep.expression("(2/3)^2").has("(2/3)² = 4/9")
    }

    @Test fun `derivatives go term by term`() {
        StepByStep.expression("d/dx(x^3+2x)").has("término a término", "d/dx(x³) = 3x²", "regla de la potencia", "3x² + 2")
    }

    @Test fun `nothing to explain gives null`() {
        assertNull(StepByStep.expression("5+5"))
        assertNull(StepByStep.expression("1.5+1"))
        assertNull(StepByStep.expression("+)"))
        assertNull(StepByStep.equation("x=x"))
        assertNull(StepByStep.system(listOf("x+y=1")))
    }

    @Test fun `linear equation moves terms and divides`() {
        val s = StepByStep.equation("2x+3=7")!!
        assertEquals("2x = 7 − 3   (el 3 pasa restando al segundo miembro)", s[0])
        assertEquals("x = 2", s.last())
        StepByStep.equation("3(x-1)=2x+4").has("3x − 3 = 2x + 4", "x = 7")
        StepByStep.equation("a*x=6", listOf("a=3")).has("3x = 6", "x = 2")
    }

    @Test fun `quadratic shows discriminant and formula`() {
        val s = StepByStep.equation("x^2-5x+6=0")!!
        s.has("a = 1, b = −5, c = 6", "Δ = b² − 4ac = (−5)² − 4·1·6 = 25 − 24 = 1", "x₁ = (5 + 1)/2 = 3", "x₂ = (5 − 1)/2 = 2")
        StepByStep.equation("x^2-2=0").has("√8 = 2√2", "±√2")
        val real = StepByStep.equation("x^2+1=0")!!
        assertTrue(real.toString(), real.last().contains("no hay raíces reales"))
        StepByStep.equation("x^2+1=0", complex = true).has("√(−4) = 2i", "±i")
    }

    @Test fun `systems by substitution or elimination`() {
        StepByStep.system(listOf("x+y=5", "x-y=1")).has("sustitución", "x = 5 − y", "y = 2", "x = 3 ; y = 2")
        StepByStep.system(listOf("2x+3y=12", "3x+2y=13")).has("reducción", "6x + 9y = 36", "5y = 10", "x = 3 ; y = 2")
    }
}
