package io.trazo.core

import io.trazo.core.math.ExactValue
import io.trazo.core.math.LineResult
import io.trazo.core.math.MathError
import io.trazo.core.math.VariableGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests para la auditoría de noviembre 2026:
 * - A2: Integrales con singularidades dentro del intervalo deben reportar divergencia.
 * - A3: solveLinear debe verificar linealidad de forma robusta y no responder si no es lineal.
 */
class AuditMathA2A3Test {

    // --- Hallazgo A2: Singularidades en integrales definidas ---

    @Test
    fun `A2 integral de 1 sobre x cuadrado entre menos 1 y 1 diverge`() {
        val results = VariableGraph.evaluate(listOf("∫_(-1)^1 1/x^2 dx"))
        val res = results.single()
        assertTrue("Debe fallar reportando que diverge", res is LineResult.Failure)
        val error = (res as LineResult.Failure).error
        assertTrue("El error debe ser Domain indicando que diverge: ${error.message}", error is MathError.Domain && error.message.contains("diverge"))
    }

    @Test
    fun `A2 integral con singularidad en cero desde o hasta el origen diverge`() {
        val fromZero = VariableGraph.evaluate(listOf("∫_0^1 1/x^2 dx")).single()
        assertTrue("∫_0^1 1/x^2 dx debe fallar", fromZero is LineResult.Failure)

        val toZero = VariableGraph.evaluate(listOf("∫_(-1)^0 1/x^2 dx")).single()
        assertTrue("∫_-1^0 1/x^2 dx debe fallar", toZero is LineResult.Failure)

        val oneOverX = VariableGraph.evaluate(listOf("∫_(-1)^1 1/x dx")).single()
        assertTrue("∫_-1^1 1/x dx debe fallar", oneOverX is LineResult.Failure)
    }

    @Test
    fun `A2 integrales regulares siguen funcionando normalmente`() {
        // Intervalo sin cero para 1/x²: [1, 2] -> 1/2
        val positive = VariableGraph.evaluate(listOf("∫_1^2 1/x^2 dx")).single()
        assertTrue(positive is LineResult.Value)
        assertEquals("1/2", (positive as LineResult.Value).value.display())

        // Polinomio integrable en [-1, 1]: x² -> 2/3
        val poly = VariableGraph.evaluate(listOf("∫_(-1)^1 x^2 dx")).single()
        assertTrue(poly is LineResult.Value)
        assertEquals("2/3", (poly as LineResult.Value).value.display())
    }

    // --- Hallazgo A3: Comprobación robusta de linealidad en solveLinear ---

    @Test
    fun `A3 ecuacion no lineal x3 menos 3x2 mas 3x igual a 5 no responde x igual a 5`() {
        // En MathNotes, la ecuación no lineal no debe ser resuelta como si x fuera 5
        val results = MathNotes.evaluate(listOf("x³ - 3x² + 3x = 5", "x ="))
        val second = results[1]
        val notFive = second !is LineResult.Value || second.value.display() != "5"
        assertTrue("x³ - 3x² + 3x = 5 no debe resolverse como x=5", notFive)

        // Mismo caso con sintaxis alternativa con exponentes estándar
        val altResults = MathNotes.evaluate(listOf("x^3 - 3*x^2 + 3*x = 5", "x ="))
        val altSecond = altResults[1]
        val altNotFive = altSecond !is LineResult.Value || altSecond.value.display() != "5"
        assertTrue("x^3 - 3*x^2 + 3*x = 5 no debe resolverse como x=5", altNotFive)
    }

    @Test
    fun `A3 ecuaciones lineales normales siguen funcionando correctamente`() {
        // 2x + 3 = 7 -> x = 2
        val res1 = MathNotes.evaluate(listOf("2x + 3 = 7", "x ="))
        assertTrue(res1[1] is LineResult.Value)
        assertEquals("2", (res1[1] as LineResult.Value).value.display())

        // 2x = 5 -> x = 5/2
        val res2 = MathNotes.evaluate(listOf("2x = 5", "x ="))
        assertTrue(res2[1] is LineResult.Value)
        assertEquals("5/2", (res2[1] as LineResult.Value).value.display())

        // x + 1 = 4 -> x = 3
        val res3 = MathNotes.evaluate(listOf("x + 1 = 4", "x ="))
        assertTrue(res3[1] is LineResult.Value)
        assertEquals("3", (res3[1] as LineResult.Value).value.display())
    }
}
