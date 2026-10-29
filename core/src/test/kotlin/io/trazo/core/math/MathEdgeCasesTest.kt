package io.trazo.core.math

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bordes del motor exacto de Math Notes P1. */
class MathEdgeCasesTest {

    private fun one(line: String) = VariableGraph.evaluate(listOf(line)).single()

    @Test fun division_por_cero_directa_y_en_racional() {
        assertEquals(MathError.DivisionByZero, assertIsFailure(one("7/0=")))
        assertEquals(MathError.DivisionByZero, assertIsFailure(one("1/(5-5)=")))
        assertEquals(MathError.DivisionByZero, assertIsFailure(one("0^-2=")))
    }

    @Test fun fracciones_grandes_se_reducen_sin_perder_precision() {
        val n = BigInteger("123456789012345678901234567890")
        val d = BigInteger("98765432109876543210987654321")
        val r = Rational.of(n * BigInteger.valueOf(6), d * BigInteger.valueOf(9))
        assertEquals(Rational.of(n * BigInteger.valueOf(2), d * BigInteger.valueOf(3)), r)
        assertEquals(Rational.of(BigInteger.ONE, BigInteger.valueOf(3)), Rational.of(n, n * BigInteger.valueOf(3)))
    }

    @Test fun potencias_racionales_exactas_y_aproximadas() {
        assertEquals("1/32", assertValue(one("2^-5=")))
        assertEquals("81/16", assertValue(one("(3/2)^4=")))
        assertTrue((one("2^(1/2)=") as LineResult.Value).value is ApproxValue)
        // Pendiente: 16^(1/2) se aproxima a 4 aunque la raíz racional es exacta.
    }

    @Test fun potencias_de_pi_se_conservan_exactas() {
        assertEquals("π²", assertValue(one("π^2=")))
        assertEquals("1/π²", assertValue(one("π^-2=")))
        // Pendiente: (π²)^(1/2) se aproxima a π en vez de conservar el valor exacto.
    }

    @Test fun errores_de_sintaxis_indican_indice_cero_based() {
        assertEquals(MathError.Syntax("la expresión está incompleta", 3), assertIsFailure(one("2+ =")))
        assertEquals(MathError.Syntax("carácter inesperado '@'", 2), assertIsFailure(one("1+@=")))
        assertEquals(MathError.Syntax("sobra un paréntesis de cierre", 5), assertIsFailure(one("(1+2))=")))
    }

    @Test fun ciclos_independientes_y_variables_ausentes() {
        val out = VariableGraph.evaluate(listOf("a=b+1", "b=a", "x=y", "y=x", "z=ausente+1", "q=99"))
        assertEquals(MathError.Cycle(listOf("a", "b")), assertIsFailure(out[0]))
        assertEquals(MathError.Cycle(listOf("x", "y")), assertIsFailure(out[2]))
        assertEquals(MathError.Undefined("ausente"), assertIsFailure(out[4]))
        assertEquals("99", (out[5] as LineResult.Assignment).value.display())
    }

    private fun assertValue(result: LineResult): String {
        assertTrue("se esperaba un valor y salió $result", result is LineResult.Value)
        return (result as LineResult.Value).value.display()
    }

    private fun assertIsFailure(result: LineResult): MathError {
        assertTrue("se esperaba un error y salió $result", result is LineResult.Failure)
        return (result as LineResult.Failure).error
    }
}
