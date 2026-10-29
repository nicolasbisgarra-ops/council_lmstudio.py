package io.trazo.core.math

import io.trazo.core.math.layout.LayoutSymbol
import io.trazo.core.math.layout.MathLayoutConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cuentas con letras sin valor y con pares: se opera lo que se puede y las letras quedan escritas. */
class SymbolicTest {
    private fun eval(text: String): MathValue =
        when (val r = VariableGraph.evaluate(listOf(text), symbolic = true).single()) {
            is LineResult.Value -> r.value
            else -> error("$text → $r")
        }

    private fun shows(expected: String, text: String) = assertEquals(text, expected, eval(text).display())

    private fun fails(text: String) {
        val r = VariableGraph.evaluate(listOf(text), symbolic = true).single()
        assertTrue("$text → $r", r is LineResult.Failure)
    }

    @Test fun `el ejemplo del par por un par con letra`() = shows("(15, 2k)", "(3;2)*(5;k)")

    @Test fun `pares elemento a elemento`() {
        shows("(4, x + 3)", "(3;2)+(1;x+1)")
        shows("(2, -k + 2)", "(3;2)-(1;k)")
        shows("(3/5, 2/k)", "(3;2)/(5;k)")
        shows("(6, 2k)", "2(3;k)")
        shows("(3/2, k/2)", "(3;k)/2")
        shows("(9, k²)", "(3;k)^2")
        shows("(-3, -k)", "-(3;k)")
        shows("(1, 2, 3x)", "(1;2;3)*(1;1;x)")
    }

    @Test fun `letras sueltas`() {
        shows("5x", "2x+3x")
        shows("x²", "x x")
        shows("6xy", "2x*3y")
        shows("3y", "6x y/(2x)")
        shows("x² + 2x + 1", "(x+1)^2")
        shows("x/2", "x/2")
        shows("2/x", "2/x")
        shows("0", "x-x")
        shows("2πr", "2π r")
        shows("x + 1", "1+x")
    }

    @Test fun `una letra definida en otro renglon vale su numero`() {
        val r = VariableGraph.evaluate(listOf("k=2", "(3;2)*(5;k)"), symbolic = true)
        assertEquals("(15, 4)", (r[1] as LineResult.Value).value.display())
    }

    @Test fun `lo que no se puede hacer con letras es un error`() {
        fails("1/(x+1)")
        fails("√x")
        fails("2^x")
        fails("(1;2)+(1;2;3)")
        fails("1+(1;2)")
        fails("x^100")
    }

    @Test fun `sin letras sueltas sigue igual que antes`() {
        val r = VariableGraph.evaluate(listOf("x+1"), symbolic = false).single()
        assertEquals(LineResult.Failure(MathError.Undefined("x")), r)
        assertEquals(ExactValue.of(Rational.of(5)), eval("2+3"))
    }

    @Test fun `una igualdad con letras sin resolver informa la letra`() {
        val r = VariableGraph.evaluate(listOf("x^2=4"), symbolic = true).single()
        assertEquals(LineResult.Failure(MathError.Undefined("x")), r)
        val same = VariableGraph.evaluate(listOf("2x+x=3x"), symbolic = true).single()
        assertTrue(same is LineResult.Check && same.holds)
    }

    @Test fun `la coma dentro de un parentesis separa un par`() {
        fun s(label: String, x: Float, y: Float = 0f, w: Float = 10f, h: Float = 20f) = LayoutSymbol(label, x, y, x + w, y + h)
        val pair = listOf(
            s("Abre paréntesis", 0f, -2f, 8f, 24f), s("3", 10f), s("Coma", 22f, 16f, 4f, 7f), s("2", 30f),
            s("Cierra paréntesis", 42f, -2f, 8f, 24f),
        )
        val text = MathLayoutConverter.convert(pair)
        assertEquals("(3;2)", text)
        assertEquals("(3, 2)", eval(text).display())
        // Fuera de un paréntesis la coma sigue siendo decimal.
        assertEquals("3.2", MathLayoutConverter.convert(listOf(s("3", 10f), s("Coma", 22f, 16f, 4f, 7f), s("2", 30f))))
    }
}
