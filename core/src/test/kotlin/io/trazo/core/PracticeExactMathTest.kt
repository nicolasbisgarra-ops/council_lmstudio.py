package io.trazo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PracticeExactMathTest {
    @Test fun `negative fractions decimals and zero are exact`() {
        assertEquals(PracticeRational.of(-1, 2), PracticeRational.parse("-0,5"))
        assertEquals(PracticeRational.of(-1, 2), PracticeRational.parse("-2/4"))
        assertEquals(PracticeRational.ZERO, PracticeRational.parse("0.000"))
        assertEquals(PracticeRational.of(1, 3), PracticeRational.of(2, 3) - PracticeRational.of(1, 3))
        assertEquals(PracticeRational.of(-1, 2), PracticeRational.of(1, 2) / PracticeRational.of(-1))
    }
    @Test fun `invalid and division by zero are rejected`() {
        for (raw in listOf("1/0", "1/(x-1)", "NaN", "1/2/3", "1e999", "2+2", "", "1".repeat(90))) {
            assertThrows(IllegalArgumentException::class.java) { PracticeRational.parse(raw) }
        }
    }
    @Test fun `linear classification is exact`() {
        val zero = PracticeRational.ZERO; val one = PracticeRational.ONE
        val unique = PracticeLinearProblem(one, zero, zero, PracticeRational.of(-1, 2))
        assertEquals(PracticeLinearSolution.Unique(PracticeRational.of(-1, 2)), unique.solve())
        assertTrue(unique.verifyAnswer("x = -0,5"))
        assertTrue(unique.verifyAnswer("-2/4"))
        assertFalse(unique.verifyAnswer("0"))
        assertFalse(unique.verifyAnswer("x=-0,5; x=2"))
        val none = PracticeLinearProblem(one, zero, one, one)
        assertEquals(PracticeLinearSolution.None, none.solve())
        assertTrue(none.verifyAnswer("Sin solución"))
        assertFalse(none.verifyAnswer("x=0"))
        val all = PracticeLinearProblem(one, one, one, one)
        assertEquals(PracticeLinearSolution.AllReal, all.solve())
        assertTrue(all.verifyAnswer("Todos los reales"))
        assertFalse(all.verifyAnswer("x=1"))
    }
    @Test fun `seeded generator yields independently verified answers and all three classes`() {
        val classes = mutableSetOf<String>()
        repeat(3000) { i ->
            val problem = PracticeLinearGenerator.generate(i.toLong())
            assertEquals(problem, PracticeLinearGenerator.generate(i.toLong()))
            when (val solution = problem.solve()) {
                is PracticeLinearSolution.Unique -> {
                    classes += "unique"
                    assertTrue(problem.verifyAnswer(solution.x.toString()))
                    assertFalse(problem.verifyAnswer((solution.x + PracticeRational.ONE).toString()))
                    assertEquals(problem.a * solution.x + problem.b, problem.c * solution.x + problem.d)
                }
                PracticeLinearSolution.None -> { classes += "none"; assertTrue(problem.verifyAnswer("sin solución")) }
                PracticeLinearSolution.AllReal -> { classes += "all"; assertTrue(problem.verifyAnswer("infinitas soluciones")) }
            }
        }
        assertEquals(setOf("unique", "none", "all"), classes)
    }
}
