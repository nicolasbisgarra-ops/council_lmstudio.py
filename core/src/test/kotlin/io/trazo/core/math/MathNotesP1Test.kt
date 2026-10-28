package io.trazo.core.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Casos de aceptación del motor de Math Notes (doc 31, etapa P1). */
class MathNotesP1Test {

    private fun eval(vararg lines: String) = VariableGraph.evaluate(lines.toList())

    private fun value(line: String): String {
        val r = eval(line).single()
        if (r !is LineResult.Value) fail("se esperaba un valor para '$line' y salió $r")
        return (r as LineResult.Value).value.display()
    }

    private fun error(line: String): MathError {
        val r = eval(line).single()
        if (r !is LineResult.Failure) fail("se esperaba un error para '$line' y salió $r")
        return (r as LineResult.Failure).error
    }

    @Test fun t01_multiplicacion() = assertEquals("96", value("12×8="))

    @Test fun t02_fracciones_exactas() = assertEquals("5/6", value("1/2+1/3="))

    @Test fun t04_area_del_circulo_se_actualiza() {
        val first = eval("r=3", "A=πr²")
        assertEquals(LineResult.Assignment("A", ExactValue.of(mapOf(1 to Rational.of(9)))), first[1])
        assertEquals("9π", (first[1] as LineResult.Assignment).value.display())
        val second = eval("r=5", "A=πr²")
        assertEquals("25π", (second[1] as LineResult.Assignment).value.display())
    }

    @Test fun t13_ciclo() {
        val out = eval("a=b+1", "b=a")
        for (r in out) {
            val e = (r as LineResult.Failure).error
            assertEquals(MathError.Cycle(listOf("a", "b")), e)
        }
        assertEquals("Definición circular: a → b → a", (out[0] as LineResult.Failure).error.message)
    }

    @Test fun autoreferencia_es_ciclo() =
        assertEquals(MathError.Cycle(listOf("x")), (eval("x=x+1").single() as LineResult.Failure).error)

    @Test fun quien_usa_un_ciclo_depende_de_un_error() {
        val out = eval("a=b", "b=a", "c=a+1")
        assertEquals(LineResult.Failure(MathError.DependsOnError("a")), out[2])
    }

    @Test fun variables_en_cualquier_orden() {
        val out = eval("y=2x", "x=4", "y+1=")
        assertEquals("8", (out[0] as LineResult.Assignment).value.display())
        assertEquals("9", (out[2] as LineResult.Value).value.display())
    }

    @Test fun variable_no_definida() = assertEquals(MathError.Undefined("z"), error("z+1="))

    @Test fun redefinicion_marca_la_segunda_linea() {
        val out = eval("x=1", "x=2", "x=")
        assertEquals(LineResult.Failure(MathError.Redefined("x", 0)), out[1])
        assertEquals("1", (out[2] as LineResult.Value).value.display())
    }

    @Test fun comprobacion() {
        val ok = eval("2+2=4").single() as LineResult.Check
        assertTrue(ok.holds)
        val bad = eval("2+2=5").single() as LineResult.Check
        assertFalse(bad.holds)
        assertTrue((eval("√2·√2=2").single() as LineResult.Check).holds)
    }

    @Test fun decimales_exactos() {
        assertEquals("3/10", value("0.1+0.2="))
        assertEquals("7/2", value("3,5="))
        assertTrue((eval("0,1+0,2=0,3").single() as LineResult.Check).holds)
    }

    @Test fun precedencia_y_signos() {
        assertEquals("-4", value("-2^2="))
        assertEquals("512", value("2^3^2="))
        assertEquals("1/8", value("2^-3="))
        assertEquals("14", value("2+3·4="))
        assertEquals("20", value("4(2+3)="))
        assertEquals("-1", value("2 − 3 ="))
    }

    @Test fun pi_y_raices() {
        assertEquals("π/2", value("π/2="))
        assertEquals("3/π", value("3/pi="))
        assertEquals("1/(2π)", value("1/(2π)="))
        assertEquals("π", value("√(π²)="))
        assertEquals("3/2", value("√(9/4)="))
        assertEquals("π + 1", value("π+1="))
        assertTrue(value("√2=").startsWith("≈ 1.414213562"))
        assertTrue(value("1/(π+1)=").startsWith("≈ "))
    }

    @Test fun errores_de_dominio() {
        assertEquals(MathError.DivisionByZero, error("1/0="))
        assertEquals(MathError.DivisionByZero, error("1/(2-2)="))
        assertEquals(MathError.DivisionByZero, error("0^-1="))
        assertTrue(error("0^0=") is MathError.Domain)
        assertTrue(error("√(-4)=") is MathError.Domain)
        assertTrue(error("(-8)^(1/3)=") is MathError.Domain)
        assertTrue(error("2^65=") is MathError.Domain)
    }

    @Test fun sintaxis_estricta() {
        assertEquals(MathError.Syntax("falta cerrar un paréntesis", 4), error("(1+2="))
        assertEquals(2, (error("1+=") as MathError.Syntax).position)
        assertTrue(error("2 3=") is MathError.Syntax)
        assertTrue(error("1=2=3") is MathError.Syntax)
        assertTrue(error("=3") is MathError.Syntax)
        assertTrue(error("2#3=") is MathError.Syntax)
        assertTrue(error("1+2)=") is MathError.Syntax)
        assertTrue(error("(2+3)4=") is MathError.Syntax)
    }

    @Test fun linea_vacia() = assertEquals(LineResult.Empty, eval("   ").single())

    @Test fun superindices_y_multiplicacion_implicita() {
        val out = eval("x=3", "2x²=", "x⁻¹=")
        assertEquals("18", (out[1] as LineResult.Value).value.display())
        assertEquals("1/3", (out[2] as LineResult.Value).value.display())
    }

    @Test fun racionales_canonicos() {
        assertEquals(Rational.of(1, 2), Rational.of(-3, -6))
        assertEquals("-1/2", Rational.of(3, -6).toString())
        assertEquals(Rational.of(3, 2), Rational.of(9, 4).exactSqrt())
        assertEquals(null, Rational.of(2).exactSqrt())
    }
}
